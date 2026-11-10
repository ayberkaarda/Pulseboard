"""Repository behaviour and role privileges against a real PostgreSQL."""

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import Engine, text
from sqlalchemy.exc import DBAPIError, ProgrammingError

from pulseboard_analytics.detectors import zscore
from pulseboard_analytics.detectors.base import Detector, DetectorResult, Severity, validate_series
from pulseboard_analytics.detectors.isolation_forest import ForestState
from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from tests.conftest import NOW, add_points, seed_metric
from tests.support.series import noisy

MOMENT = datetime(2026, 6, 1, 10, 0, tzinfo=UTC)


def _result(
    offset_minutes: int = 0,
    detector: Detector = Detector.ZSCORE,
    severity: Severity = Severity.HIGH,
) -> DetectorResult:
    ts = MOMENT + timedelta(minutes=offset_minutes)
    return DetectorResult(
        detector=detector,
        window_start=ts,
        window_end=ts,
        score=0.9,
        severity=severity,
        details={"z": 5.5},
    )


def _anomaly_count(owner: Engine) -> int:
    with owner.connect() as conn:
        return int(conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one())


def _metric(reader_engine: Engine, metric_id: UUID) -> MetricRef:
    metric = MetricReader(reader_engine).get_metric(metric_id)
    assert metric is not None
    return metric


def test_reader_lists_only_metrics_with_recent_points(owner: Engine, reader_engine: Engine) -> None:
    _, recent = seed_metric(owner, noisy(10))
    _, stale = seed_metric(owner, noisy(10), end=NOW - timedelta(days=3))

    active = MetricReader(reader_engine).active_metrics(NOW - timedelta(hours=24))

    assert [m.id for m in active] == [recent]
    assert stale not in [m.id for m in active]


def test_reader_returns_tenant_and_none_for_unknown_metric(
    owner: Engine, reader_engine: Engine
) -> None:
    tenant, metric = seed_metric(owner, noisy(3))
    reader = MetricReader(reader_engine)

    found = reader.get_metric(metric)

    assert found is not None
    assert (found.id, found.tenant_id) == (metric, tenant)
    assert reader.get_metric(uuid4()) is None


def test_series_is_sorted_utc_and_validates(owner: Engine, reader_engine: Engine) -> None:
    _, metric = seed_metric(owner, noisy(20))

    frame = MetricReader(reader_engine).load_series(metric, NOW - timedelta(hours=24))

    validate_series(frame)
    assert len(frame) == 20
    assert frame["ts"].is_monotonic_increasing


def test_series_window_excludes_older_points_and_empty_window_is_valid(
    owner: Engine, reader_engine: Engine
) -> None:
    _, metric = seed_metric(owner, noisy(10), end=NOW - timedelta(days=2))

    empty = MetricReader(reader_engine).load_series(metric, NOW - timedelta(hours=24))

    validate_series(empty)
    assert len(empty) == 0
    assert zscore.detect(empty) == []


def test_late_points_inside_the_window_are_included(owner: Engine, reader_engine: Engine) -> None:
    _, metric = seed_metric(owner, [1.0, 2.0, 3.0], end=NOW - timedelta(minutes=5))
    add_points(owner, metric, [9.0], end=NOW - timedelta(hours=2))

    frame = MetricReader(reader_engine).load_series(metric, NOW - timedelta(hours=24))

    assert len(frame) == 4
    assert frame["ts"].is_monotonic_increasing
    assert frame["value"].iloc[0] == 9.0


def test_insert_is_idempotent_and_returns_only_new_rows(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    tenant, metric_id = seed_metric(owner, noisy(5))
    metric = _metric(reader_engine, metric_id)
    writer = AnomalyWriter(writer_engine)
    batch = [_result(0), _result(5), _result(10)]

    first = writer.insert_new(metric, batch)
    second = writer.insert_new(metric, batch)
    third = writer.insert_new(metric, [*batch, _result(15)])

    assert len(first) == 3
    assert second == []
    assert [row.window_start for row in third] == [MOMENT + timedelta(minutes=15)]
    assert _anomaly_count(owner) == 4
    assert {row.tenant_id for row in first} == {tenant}
    assert all(row.metric_id == metric_id and row.detector == "ZSCORE" for row in first)


def test_same_window_for_another_detector_is_a_separate_row(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    _, metric_id = seed_metric(owner, noisy(5))
    metric = _metric(reader_engine, metric_id)

    inserted = AnomalyWriter(writer_engine).insert_new(
        metric, [_result(0), _result(0, Detector.ISOLATION_FOREST, Severity.LOW)]
    )

    assert len(inserted) == 2


def test_insert_for_a_metric_of_another_tenant_is_rejected(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    _, metric_id = seed_metric(owner, noisy(5))
    real = _metric(reader_engine, metric_id)
    forged = MetricRef(id=real.id, tenant_id=uuid4())

    with pytest.raises(DBAPIError):
        AnomalyWriter(writer_engine).insert_new(forged, [_result()])

    assert _anomaly_count(owner) == 0


def test_state_round_trip_and_upsert(owner: Engine, writer_engine: Engine) -> None:
    _, metric = seed_metric(owner, noisy(5))
    store = DetectorStateStore(writer_engine)
    assert store.get(metric, Detector.ISOLATION_FOREST) is None
    first = ForestState(trained_at=MOMENT, version=1, contamination=0.02, point_count=288)
    second = ForestState(
        trained_at=MOMENT + timedelta(hours=6), version=2, contamination=0.02, point_count=300
    )

    store.upsert(metric, Detector.ISOLATION_FOREST, first)
    assert store.get(metric, Detector.ISOLATION_FOREST) == first
    store.upsert(metric, Detector.ISOLATION_FOREST, second)

    assert store.get(metric, Detector.ISOLATION_FOREST) == second
    with owner.connect() as conn:
        params = conn.execute(text("SELECT params FROM detector_state")).scalar_one()
        rows = conn.execute(text("SELECT count(*) FROM detector_state")).scalar_one()
    assert rows == 1
    assert set(params) == {"trained_at", "version", "contamination", "point_count"}
    assert params["version"] == 2
    assert params["contamination"] == 0.02


def _denied(engine: Engine, sql: str, params: dict[str, object] | None = None) -> None:
    with pytest.raises(ProgrammingError, match="permission denied"), engine.begin() as conn:
        conn.execute(text(sql), params or {})


def _seed_anomaly(owner: Engine, tenant: UUID, metric: UUID) -> None:
    with owner.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score,"
                " detector, severity) VALUES (:id, :t, :m, :ts, :ts, 0.9, 'ZSCORE', 'HIGH')"
            ),
            {"id": uuid4(), "t": tenant, "m": metric, "ts": MOMENT},
        )


def test_writer_cannot_read_other_tables(owner: Engine, writer_engine: Engine) -> None:
    for table in ("metric_point", "metric_definition", "app_user", "tenant"):
        _denied(writer_engine, f"SELECT * FROM {table}")


def test_writer_cannot_delete_update_or_truncate_anomalies(
    owner: Engine, writer_engine: Engine
) -> None:
    tenant, metric = seed_metric(owner, noisy(3))
    _seed_anomaly(owner, tenant, metric)

    _denied(writer_engine, "DELETE FROM anomaly")
    _denied(writer_engine, "UPDATE anomaly SET severity = 'LOW'")
    _denied(writer_engine, "TRUNCATE anomaly")
    _denied(writer_engine, "UPDATE anomaly SET acknowledged_at = now()")

    assert _anomaly_count(owner) == 1


def test_writer_cannot_delete_detector_state_or_write_catalogue_data(
    owner: Engine, writer_engine: Engine
) -> None:
    _, metric = seed_metric(owner, noisy(3))

    _denied(writer_engine, "DELETE FROM detector_state")
    _denied(
        writer_engine,
        "INSERT INTO metric_point (metric_id, ts, value) VALUES (:m, now(), 1)",
        {"m": metric},
    )
    _denied(
        writer_engine,
        "INSERT INTO metric_definition (id, tenant_id) VALUES (:i, :t)",
        {"i": uuid4(), "t": uuid4()},
    )


def test_reader_cannot_write_or_read_anomalies(owner: Engine, reader_engine: Engine) -> None:
    tenant, metric = seed_metric(owner, noisy(3))
    _seed_anomaly(owner, tenant, metric)

    _denied(reader_engine, "SELECT * FROM anomaly")
    _denied(reader_engine, "SELECT * FROM detector_state")
    _denied(reader_engine, "SELECT * FROM app_user")
    _denied(
        reader_engine,
        "INSERT INTO anomaly (id, tenant_id, metric_id, window_start, window_end, score,"
        " detector, severity) VALUES (:id, :t, :m, now(), now(), 0.5, 'ZSCORE', 'LOW')",
        {"id": uuid4(), "t": tenant, "m": metric},
    )
    _denied(reader_engine, "DELETE FROM metric_point")
    _denied(
        reader_engine,
        "INSERT INTO metric_point (metric_id, ts, value) VALUES (:m, now(), 1)",
        {"m": metric},
    )
    _denied(reader_engine, "UPDATE metric_definition SET tenant_id = tenant_id")


def test_reader_can_read_points_and_metric_definitions(
    owner: Engine, reader_engine: Engine
) -> None:
    seed_metric(owner, noisy(3))

    with reader_engine.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM metric_point")).scalar_one() == 3
        assert conn.execute(text("SELECT count(*) FROM metric_definition")).scalar_one() == 1
