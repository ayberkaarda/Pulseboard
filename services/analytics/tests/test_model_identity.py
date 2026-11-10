"""Model identity (compare-and-set), bounded per-metric locks, raw-count retraining, failures."""

import json
import threading
import time
import uuid
from datetime import UTC, datetime, timedelta

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from redis import Redis
from sqlalchemy import Engine, text

from pulseboard_analytics.budget import Budget
from pulseboard_analytics.detectors import isolation_forest, zscore
from pulseboard_analytics.detectors.base import Detector
from pulseboard_analytics.jobs.score_all import score_all_metrics
from pulseboard_analytics.jobs.scoring import MetricScorer
from pulseboard_analytics.locks import KeyedLocks, MetricBusyError
from pulseboard_analytics.main import create_app
from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.reader import MetricReader, SeriesWindow
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from pulseboard_analytics.security import SIGNATURE_HEADER, sign
from pulseboard_analytics.settings import Settings
from tests.conftest import NOW, SECRET, seed_metric
from tests.support.fake_server import FakeApiCore, fake_api_core
from tests.support.series import seasonal
from tests.test_scoring_limits import Recorder, Ticker, _seed_ref, _spiky


class Clock:
    def __call__(self) -> datetime:
        return NOW


class _SkewedReader(MetricReader):
    """Another process with another view of the data: every value is shifted."""

    def __init__(self, engine: Engine, shift: float) -> None:
        super().__init__(engine)
        self._shift = shift

    def load_window(
        self, metric_id: uuid.UUID, since: datetime, until: datetime, budget: Budget | None = None
    ) -> SeriesWindow:
        window = super().load_window(metric_id, since, until, budget)
        frame = window.frame.copy()
        frame["value"] = frame["value"] + self._shift * np.arange(len(frame)) % 7.0
        return SeriesWindow(frame, window.raw_count, window.bucket_seconds)


class _BarrierStates(DetectorStateStore):
    """Holds every caller after its read until all parties have read (or the wait times out)."""

    barrier: threading.Barrier

    def get(
        self, metric_id: uuid.UUID, detector: Detector, budget: Budget | None = None
    ) -> isolation_forest.ForestState | None:
        state = super().get(metric_id, detector, budget)
        try:
            self.barrier.wait(timeout=0.5)
        except threading.BrokenBarrierError:
            pass
        return state


def _scorer_with(
    reader: MetricReader, writer_engine: Engine, states: DetectorStateStore | None = None
) -> MetricScorer:
    return MetricScorer(
        reader=reader,
        anomalies=AnomalyWriter(writer_engine),
        states=states if states is not None else DetectorStateStore(writer_engine),
        clock=Clock(),
    )


def test_two_processes_with_different_data_agree_on_one_snapshot_of_record(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner)
    barrier = threading.Barrier(2)
    scorers = []
    for shift in (0.0, 3.0):
        states = _BarrierStates(writer_engine)
        states.barrier = barrier
        reader = (
            MetricReader(reader_engine) if shift == 0.0 else _SkewedReader(reader_engine, shift)
        )
        scorers.append(_scorer_with(reader, writer_engine, states))
    outcomes = {}
    errors: list[BaseException] = []

    def work(index: int) -> None:
        try:
            outcomes[index] = scorers[index].score_metric(metric)
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    threads = [threading.Thread(target=work, args=(i,)) for i in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=120)

    assert errors == []
    forests = [scorer._forests[metric.id] for scorer in scorers]
    assert forests[0].state.data_hash != forests[1].state.data_hash
    with owner.connect() as conn:
        rows = conn.execute(text("SELECT version, params FROM detector_state")).all()
    assert len(rows) == 1
    version, params = rows[0]
    assert version == 1
    winners = [f for f in forests if not f.local]
    losers = [f for f in forests if f.local]
    assert len(winners) == 1 and len(losers) == 1
    # the row of record describes exactly the winner's training snapshot
    assert params["data_hash"] == winners[0].state.data_hash
    assert params["point_count"] == winners[0].state.point_count
    # the loser adopted the winner's identity, carries version 1 but did not claim it
    assert losers[0].adopted == winners[0].state.identity
    assert losers[0].persisted_identity == winners[0].persisted_identity
    assert losers[0].state.version == 1
    # the reported details tell the two apart
    for index, scorer in enumerate(scorers):
        details = [
            r.details for r in outcomes[index].results if r.detector is Detector.ISOLATION_FOREST
        ]
        local = scorer._forests[metric.id].local
        assert all(("model_local" in d) == local for d in details)
        assert all(("model_version" in d) != local for d in details)


def test_a_later_run_of_the_loser_reuses_its_local_model_without_writing(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    metric = _seed_ref(owner)
    _scorer_with(MetricReader(reader_engine), writer_engine).score_metric(metric)
    loser = _scorer_with(_SkewedReader(reader_engine, 2.0), writer_engine)
    loser.score_metric(metric)
    trained: list[int] = []
    real = isolation_forest.train

    def counting(*args: object, **kwargs: object) -> isolation_forest.TrainedForest:
        trained.append(1)
        return real(*args, **kwargs)  # type: ignore[arg-type]

    monkeypatch.setattr(isolation_forest, "train", counting)

    loser.score_metric(metric)

    assert trained == []
    assert loser._forests[metric.id].local


def test_the_content_identity_depends_on_data_count_and_time() -> None:
    values = seasonal(120)
    frame = pd.DataFrame(
        {"ts": pd.date_range("2026-01-01", periods=120, freq="5min", tz="UTC"), "value": values}
    )
    other = frame.assign(value=frame["value"] + 1e-9 * np.arange(120))

    one = isolation_forest.train(frame, trained_at=NOW, version=1).state
    same = isolation_forest.train(frame, trained_at=NOW, version=7).state
    changed = isolation_forest.train(other, trained_at=NOW, version=1).state
    later = isolation_forest.train(frame, trained_at=NOW + timedelta(seconds=1), version=1).state

    assert one.identity == same.identity
    assert len({one.identity, changed.identity, later.identity}) == 3
    assert one.point_count == 120 and len(one.data_hash) == 64


# --- bounded per-metric locks ---------------------------------------------------------------


def test_the_second_caller_of_one_key_fails_after_the_timeout_and_nothing_leaks() -> None:
    locks = KeyedLocks()
    entered = threading.Event()
    release = threading.Event()

    def holder() -> None:
        with locks.hold("m", 1.0):
            entered.set()
            release.wait(timeout=10)

    thread = threading.Thread(target=holder)
    thread.start()
    assert entered.wait(timeout=5)

    started = time.monotonic()
    with pytest.raises(MetricBusyError), locks.hold("m", 0.2):
        pytest.fail("must not be acquired")
    waited = time.monotonic() - started
    with locks.hold("other", 0.0):  # an unrelated key is never blocked
        assert len(locks) == 2

    release.set()
    thread.join(timeout=5)
    assert 0.15 <= waited < 3.0
    assert len(locks) == 0


def test_a_busy_metric_fails_cleanly_and_does_not_block_other_metrics(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    busy, free = _seed_ref(owner), _seed_ref(owner)
    scorer = _scorer_with(MetricReader(reader_engine), writer_engine)
    scorer.lock_timeout_seconds = 0.2
    entered = threading.Event()
    release = threading.Event()

    def holder() -> None:
        with scorer._locks.hold(busy.id, 1.0):
            entered.set()
            release.wait(timeout=30)

    thread = threading.Thread(target=holder)
    thread.start()
    assert entered.wait(timeout=5)
    try:
        started = time.monotonic()
        with pytest.raises(MetricBusyError):
            scorer.score_metric(busy)
        assert time.monotonic() - started < 5.0
        assert scorer.score_metric(free).inserted  # a different metric is not affected
        summary = score_all_metrics(
            reader=MetricReader(reader_engine),
            scorer=scorer,
            notifier=Recorder(),
            redis=redis_client,
            lock_ttl_ms=55_000,
            monotonic=Ticker(),
        )
    finally:
        release.set()
        thread.join(timeout=10)

    assert summary.metrics == 2
    assert summary.failed_metrics == 1
    assert summary.unscored_metrics == 0
    with owner.connect() as conn:
        states = conn.execute(text("SELECT metric_id FROM detector_state")).scalars().all()
    assert states == [free.id]  # the busy metric was left untrained for the next run


# --- retraining counts raw observations ------------------------------------------------------


def _insert_series(owner: Engine, metric: uuid.UUID, values: np.ndarray, offset: float) -> None:
    count = len(values)
    last = NOW - timedelta(seconds=10 - offset)
    rows = [
        {"m": metric, "ts": last - timedelta(seconds=30 * (count - 1 - i)), "v": float(v)}
        for i, v in enumerate(values)
    ]
    with owner.begin() as conn:
        conn.execute(
            text("INSERT INTO metric_point (metric_id, ts, value) VALUES (:m, :ts, :v)"), rows
        )


def _state(owner: Engine) -> tuple[int, int]:
    with owner.connect() as conn:
        row = conn.execute(
            text("SELECT version, (params->>'point_count')::int FROM detector_state")
        ).one()
    return int(row[0]), int(row[1])


def test_retraining_compares_the_raw_count_not_the_number_of_buckets(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    rng = np.random.default_rng(5)
    tenant, metric = seed_metric(owner, [0.0])
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM metric_point"))
    ref = MetricRef(id=metric, tenant_id=tenant)
    _insert_series(owner, metric, 10.0 + rng.normal(0.0, 0.5, 2800), offset=0.0)
    scorer = _scorer_with(MetricReader(reader_engine, max_points=100), writer_engine)

    scorer.score_metric(ref)
    version, points = _state(owner)
    assert (version, points) == (1, 2800)  # raw observations, not about 97 buckets

    # 10 % more raw points inside the same buckets: below the growth threshold
    _insert_series(owner, metric, 10.0 + rng.normal(0.0, 0.5, 280), offset=0.5)
    scorer.score_metric(ref)
    assert _state(owner) == (1, 2800)

    # 25 % more raw points in total: the bucket count is unchanged but the raw count grew
    _insert_series(owner, metric, 10.0 + rng.normal(0.0, 0.5, 420), offset=0.25)
    scorer.score_metric(ref)
    version, points = _state(owner)
    assert version == 2
    assert points == 3500


def test_needs_retrain_uses_the_given_count() -> None:
    state = isolation_forest.ForestState(
        trained_at=NOW, version=1, contamination=0.02, point_count=1000
    )

    assert not isolation_forest.needs_retrain(state, 1199, NOW)
    assert isolation_forest.needs_retrain(state, 1200, NOW)


# --- every detector failing is a failure --------------------------------------------------------


def _break_both(monkeypatch: pytest.MonkeyPatch) -> None:
    def broken(*args: object, **kwargs: object) -> list[object]:
        raise RuntimeError("numeric trouble")

    monkeypatch.setattr(zscore, "detect", broken)
    monkeypatch.setattr(isolation_forest, "train", broken)


def test_when_every_detector_fails_the_outcome_carries_the_errors(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    metric = _seed_ref(owner)
    _break_both(monkeypatch)

    outcome = _scorer_with(MetricReader(reader_engine), writer_engine).score_metric(metric)

    assert outcome.failed
    assert set(outcome.errors) == {Detector.ZSCORE, Detector.ISOLATION_FOREST}
    assert outcome.results == []


def test_one_working_detector_is_not_a_failed_metric(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    metric = _seed_ref(owner)
    monkeypatch.setattr(zscore, "detect", lambda frame: (_ for _ in ()).throw(RuntimeError("x")))

    outcome = _scorer_with(MetricReader(reader_engine), writer_engine).score_metric(metric)

    assert not outcome.failed
    assert outcome.errors == (Detector.ZSCORE,)


def test_score_all_counts_a_metric_whose_detectors_all_failed(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    redis_client: Redis,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _seed_ref(owner)
    _seed_ref(owner, np.full(288, 4.0))  # flat: z-score runs, no forest, nothing fails
    _break_both(monkeypatch)

    summary = score_all_metrics(
        reader=MetricReader(reader_engine),
        scorer=_scorer_with(MetricReader(reader_engine), writer_engine),
        notifier=Recorder(),
        redis=redis_client,
        lock_ttl_ms=55_000,
        monotonic=Ticker(),
    )

    assert summary.metrics == 2
    assert summary.failed_metrics == 2


def test_the_manual_endpoint_reports_a_failed_analysis_instead_of_an_empty_success(
    settings: Settings,
    owner: Engine,
    redis_client: Redis,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = datetime.now(UTC)
    _, metric = seed_metric(owner, _spiky(), end=now - timedelta(minutes=5))
    _break_both(monkeypatch)
    body = json.dumps({"metricId": str(metric)}).encode()
    with fake_api_core() as server, TestClient(create_app(_with_core(settings, server))) as client:
        response = client.post(
            "/internal/v1/score", content=body, headers={SIGNATURE_HEADER: sign(SECRET, body)}
        )

    assert response.status_code == 502
    assert response.json() == {"detail": "analysis failed: all detectors failed"}
    assert "numeric trouble" not in response.text
    with owner.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one() == 0


def _with_core(settings: Settings, server: FakeApiCore) -> Settings:
    return Settings(
        database_reader_url=settings.database_reader_url,
        database_writer_url=settings.database_writer_url,
        redis_url=settings.redis_url,
        api_core_base_url=server.url,
        internal_shared_secret=SECRET,
        scheduler_enabled=False,
    )
