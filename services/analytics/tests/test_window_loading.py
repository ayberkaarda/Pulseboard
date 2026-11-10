"""Bounded single-snapshot window loading and extreme-preserving bucketing."""

import uuid
from datetime import timedelta

import numpy as np
from sqlalchemy import Engine, event, text

from pulseboard_analytics.detectors.base import Detector
from pulseboard_analytics.jobs.scoring import MetricScorer
from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from tests.conftest import NOW, seed_metric
from tests.support.series import seasonal

SINCE = NOW - timedelta(hours=24)


class Clock:
    def __call__(self):  # type: ignore[no-untyped-def]
        return NOW


def _insert_dense(owner: Engine, metric: uuid.UUID, values: np.ndarray, step_seconds: int) -> None:
    """One point every ``step_seconds`` seconds, the last 10 seconds before NOW."""
    count = len(values)
    last = NOW - timedelta(seconds=10)
    rows = [
        {"m": metric, "ts": last - timedelta(seconds=step_seconds * (count - 1 - i)), "v": float(v)}
        for i, v in enumerate(values)
    ]
    with owner.begin() as conn:
        conn.execute(
            text("INSERT INTO metric_point (metric_id, ts, value) VALUES (:m, :ts, :v)"), rows
        )


def test_a_short_spike_in_a_dense_bucket_is_detected_at_its_real_timestamp(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    count = 2800  # one point every 30 s, about 29 points per bucket
    rng = np.random.default_rng(11)
    values = 10.0 + rng.normal(0.0, 0.5, count)
    spike_index = 1500
    values[spike_index] += 6.0  # raw z about 12; a bucket mean would shift by only 0.2 (z about 2)
    tenant, metric = seed_metric(owner, [0.0])
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM metric_point"))
    _insert_dense(owner, metric, values, 30)
    spike_ts = NOW - timedelta(seconds=10 + 30 * (count - 1 - spike_index))
    scorer = MetricScorer(
        reader=MetricReader(reader_engine, max_points=100),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )

    outcome = scorer.score_metric(MetricRef(id=metric, tenant_id=tenant))

    hits = [r for r in outcome.results if r.detector is Detector.ZSCORE]
    at_spike = [r for r in hits if r.window_start == spike_ts]
    assert at_spike, [r.window_start for r in hits]
    assert at_spike[0].details["value"] == values[spike_index]
    assert at_spike[0].details["bucketed"] is True
    assert at_spike[0].details["bucketSeconds"] == 873
    assert 20 <= int(at_spike[0].details["rawPoints"]) <= 40


def test_raw_windows_carry_no_bucket_marker(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    values = seasonal(288)
    values[250] += 80.0
    tenant, metric = seed_metric(owner, values)
    scorer = MetricScorer(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )

    outcome = scorer.score_metric(MetricRef(id=metric, tenant_id=tenant))

    assert outcome.results
    assert all("bucketed" not in r.details for r in outcome.results)


def test_a_bucket_is_represented_by_the_real_point_farthest_from_its_median(
    owner: Engine, reader_engine: Engine
) -> None:
    _, metric = seed_metric(owner, [0.0])
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM metric_point"))
    # ten points per bucket of 100 s when 1000 s is split into 10 buckets (width 112 s here)
    values = np.full(300, 5.0)
    values[41] = -30.0
    _insert_dense(owner, metric, values, 20)
    reader = MetricReader(reader_engine, max_points=50)

    window = reader.load_window(metric, SINCE, NOW)

    assert window.bucket_seconds is not None
    assert window.raw_count == 300
    chosen = window.frame[window.frame["value"] == -30.0]
    assert len(chosen) == 1
    assert chosen["ts"].iloc[0].to_pydatetime() == NOW - timedelta(seconds=10 + 20 * (299 - 41))
    assert int(window.frame["raw_points"].sum()) == 300
    assert window.frame["ts"].is_monotonic_increasing
    assert len(window.frame) <= 50


def _late_points(owner: Engine, metric: uuid.UUID, count: int) -> None:
    with owner.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO metric_point (metric_id, ts, value) "
                "SELECT :m, :base + make_interval(secs => g + 0.5), 1.0 "
                "FROM generate_series(1, :n) AS g"
            ),
            {"m": metric, "base": NOW - timedelta(hours=23), "n": count},
        )


def test_the_window_is_loaded_in_one_snapshot_and_materialization_stays_bounded(
    owner: Engine, reader_engine: Engine
) -> None:
    _, metric = seed_metric(owner, seasonal(150))
    reader = MetricReader(reader_engine, max_points=100)
    statements: list[str] = []
    fetched: list[int] = []
    injected: list[bool] = []

    @event.listens_for(reader_engine, "after_cursor_execute")
    def after(conn, cursor, statement, parameters, context, executemany):  # type: ignore[no-untyped-def]
        if "metric_point" not in statement:
            return
        statements.append(statement)
        fetched.append(cursor.rowcount)
        if not injected:  # between the first and the second statement of the same load
            injected.append(True)
            _late_points(owner, metric, 5000)

    try:
        window = reader.load_window(metric, SINCE, NOW)
    finally:
        event.remove(reader_engine, "after_cursor_execute", after)

    assert "LIMIT" in statements[0].upper()
    assert "count(" not in statements[0].lower()
    assert max(fetched) <= 101
    assert len(statements) == 2
    # the 5000 late points were committed after the snapshot was taken
    assert window.raw_count == 150
    assert len(window.frame) <= 100
    # a second load sees them, still bounded
    again = reader.load_window(metric, SINCE, NOW)
    assert again.raw_count == 5150
    assert len(again.frame) <= 100


def test_late_points_committed_before_the_first_statement_cannot_inflate_the_frame(
    owner: Engine, reader_engine: Engine
) -> None:
    _, metric = seed_metric(owner, seasonal(99))
    reader = MetricReader(reader_engine, max_points=100)
    _late_points(owner, metric, 30_000)

    window = reader.load_window(metric, SINCE, NOW)

    assert len(window.frame) <= 100
    assert window.raw_count == 30_099
