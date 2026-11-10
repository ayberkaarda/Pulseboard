"""Resource bounds, lease handling, atomic training and window rules of the scoring pipeline."""

import json
import threading
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import numpy as np
import pytest
from redis import Redis
from sqlalchemy import Engine, text

from pulseboard_analytics.budget import Budget
from pulseboard_analytics.clients.api_core import NotifyResult
from pulseboard_analytics.detectors import isolation_forest, zscore
from pulseboard_analytics.detectors.base import Detector
from pulseboard_analytics.jobs.score_all import (
    LOCK_KEY,
    NOTIFY_BATCH,
    RunSummary,
    score_all_metrics,
)
from pulseboard_analytics.jobs.scoring import MetricScorer, ScoreOutcome
from pulseboard_analytics.locks import RedisLock
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from tests.conftest import NOW, add_points, seed_metric
from tests.support.series import seasonal

LOCK_TTL_MS = 55_000


class Clock:
    def __call__(self) -> datetime:
        return NOW


class Ticker:
    """A settable monotonic clock in seconds."""

    def __init__(self) -> None:
        self.seconds = 0.0

    def __call__(self) -> float:
        return self.seconds


class Recorder:
    def __init__(self) -> None:
        self.calls: list[list[StoredAnomaly]] = []

    def notify(self, anomalies: list[StoredAnomaly], budget: Budget | None = None) -> NotifyResult:
        self.calls.append(list(anomalies))
        return NotifyResult(delivered=len(anomalies), failed=0)


def _scorer(
    reader_engine: Engine, writer_engine: Engine, *, max_forests: int = 256
) -> MetricScorer:
    return MetricScorer(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
        max_forests=max_forests,
    )


def _spiky(count: int = 288, at: int = 250, size: float = 80.0) -> np.ndarray:
    values = seasonal(count)
    values[at] += size
    return values


def _rows(owner: Engine, sql: str) -> list[tuple[object, ...]]:
    with owner.connect() as conn:
        return [tuple(row) for row in conn.execute(text(sql)).all()]


def _seed_ref(owner: Engine, values: np.ndarray | None = None) -> MetricRef:
    tenant, metric = seed_metric(owner, _spiky() if values is None else values)
    return MetricRef(id=metric, tenant_id=tenant)


def _fake_anomaly(metric: MetricRef) -> StoredAnomaly:
    now = datetime.now(UTC)
    return StoredAnomaly(
        id=uuid.uuid4(),
        tenant_id=metric.tenant_id,
        metric_id=metric.id,
        window_start=now,
        window_end=now,
        score=0.9,
        detector="ZSCORE",
        severity="HIGH",
        created_at=now,
    )


def _run_all(
    reader_engine: Engine,
    scorer: MetricScorer,
    redis: Redis,
    notifier: object | None = None,
    ticker: Ticker | None = None,
    **extra: Any,
) -> RunSummary:
    return score_all_metrics(
        reader=MetricReader(reader_engine),
        scorer=scorer,
        notifier=notifier if notifier is not None else Recorder(),  # type: ignore[arg-type]
        redis=redis,
        lock_ttl_ms=LOCK_TTL_MS,
        monotonic=ticker if ticker is not None else Ticker(),
        **extra,
    )


# --- finding 1: bounded cache and incremental delivery ---------------------------------------


def test_forest_cache_is_bounded_and_evicts_the_least_recently_scored(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    metrics = [_seed_ref(owner) for _ in range(5)]
    scorer = _scorer(reader_engine, writer_engine, max_forests=3)

    for metric in metrics:
        scorer.score_metric(metric)
        assert scorer.cached_forests <= 3
    scorer.score_metric(metrics[2])
    scorer.score_metric(metrics[0])

    assert scorer.cached_forests == 3
    assert set(scorer._forests) == {metrics[4].id, metrics[2].id, metrics[0].id}


def test_retain_drops_forests_of_metrics_that_are_no_longer_active(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    metrics = [_seed_ref(owner) for _ in range(3)]
    scorer = _scorer(reader_engine, writer_engine)
    for metric in metrics:
        scorer.score_metric(metric)

    dropped = scorer.retain({metrics[1].id})

    assert dropped == 2
    assert set(scorer._forests) == {metrics[1].id}


def test_a_run_forgets_forests_of_metrics_missing_from_its_active_set(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    old = _seed_ref(owner)
    scorer = _scorer(reader_engine, writer_engine)
    _run_all(reader_engine, scorer, redis_client)
    assert old.id in scorer._forests
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM metric_point WHERE metric_id = :m"), {"m": old.id})
    _seed_ref(owner)

    _run_all(reader_engine, scorer, redis_client)

    assert old.id not in scorer._forests
    assert scorer.cached_forests == 1


class _CountingScorer(MetricScorer):
    """Pretends every metric stored 300 anomalies and records the delivery progress."""

    delivered_before: list[int]
    recorder: Recorder

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        self.delivered_before.append(sum(len(c) for c in self.recorder.calls))
        return ScoreOutcome(results=[], inserted=[_fake_anomaly(metric) for _ in range(300)])


def test_anomalies_are_delivered_in_bounded_batches_while_the_run_is_in_progress(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(6):
        _seed_ref(owner)
    scorer = _CountingScorer(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )
    scorer.delivered_before = []
    scorer.recorder = Recorder()

    summary = _run_all(reader_engine, scorer, redis_client, scorer.recorder)

    sizes = [len(call) for call in scorer.recorder.calls]
    assert summary.inserted == 1800 == summary.notified
    assert sizes == [NOTIFY_BATCH, NOTIFY_BATCH, NOTIFY_BATCH, 300]
    # 600 anomalies are pending when the third metric starts, so a batch is already out.
    assert scorer.delivered_before[2] == NOTIFY_BATCH
    assert scorer.delivered_before[5] > scorer.delivered_before[2]


def test_a_crashing_notifier_keeps_the_stored_rows(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    _seed_ref(owner)

    class Crashing:
        def notify(
            self, anomalies: list[StoredAnomaly], budget: Budget | None = None
        ) -> NotifyResult:
            raise RuntimeError("down")

    summary = _run_all(
        reader_engine, _scorer(reader_engine, writer_engine), redis_client, Crashing()
    )

    assert summary.inserted >= 1
    assert summary.notified == 0
    assert len(_rows(owner, "SELECT 1 FROM anomaly")) == summary.inserted


# --- finding 2: lease and deadline ------------------------------------------------------------


class _Probe(MetricScorer):
    """Records which metrics were scored and runs a hook after the first one."""

    seen: list[uuid.UUID]
    hook: object = None

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        self.seen.append(metric.id)
        if len(self.seen) == 1 and callable(self.hook):
            self.hook()
        return ScoreOutcome(results=[], inserted=[])


def _probe(reader_engine: Engine, writer_engine: Engine, hook: object = None) -> _Probe:
    probe = _Probe(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )
    probe.seen = []
    probe.hook = hook
    return probe


def test_run_stops_when_the_deadline_passes_and_leaves_the_rest_for_the_next_run(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    probe = _probe(reader_engine, writer_engine, lambda: setattr(ticker, "seconds", 50.0))

    summary = _run_all(reader_engine, probe, redis_client, ticker=ticker)

    assert len(probe.seen) == 1
    assert summary.metrics == 3
    assert summary.unscored_metrics == 2
    assert redis_client.get(LOCK_KEY) is None
    follow_up = _probe(reader_engine, writer_engine)
    assert _run_all(reader_engine, follow_up, redis_client).unscored_metrics == 0
    assert len(follow_up.seen) == 3


def test_run_stops_when_the_local_lease_estimate_runs_out(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    probe = _probe(reader_engine, writer_engine, lambda: setattr(ticker, "seconds", 56.0))

    summary = _run_all(reader_engine, probe, redis_client, ticker=ticker, deadline_seconds=1_000.0)

    assert len(probe.seen) == 1
    assert summary.unscored_metrics == 2


def test_two_runs_never_both_continue_after_the_lease_changed_hands(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    """A is mid-run, its lease expires, B takes the key: A must not score anything further."""
    for _ in range(3):
        _seed_ref(owner)

    def expire_and_hand_over() -> None:
        redis_client.delete(LOCK_KEY)  # the TTL ran out in Redis
        assert RedisLock(redis_client, LOCK_KEY, LOCK_TTL_MS).acquire()  # B acquired it

    probe_a = _probe(reader_engine, writer_engine, expire_and_hand_over)

    summary_a = _run_all(reader_engine, probe_a, redis_client)

    assert len(probe_a.seen) == 1
    assert summary_a.unscored_metrics == 2
    # A's cleanup did not touch B's lease, so B keeps its exclusive protection.
    assert redis_client.get(LOCK_KEY) is not None
    probe_b = _probe(reader_engine, writer_engine)
    assert _run_all(reader_engine, probe_b, redis_client).skipped
    assert probe_b.seen == []


def test_lock_lease_is_lost_when_the_token_changes_or_the_time_is_up(redis_client: Redis) -> None:
    ticker = Ticker()
    lock = RedisLock(redis_client, "k", 1_000, ticker)
    assert lock.acquire()
    assert lock.held()
    ticker.seconds = 0.5
    assert lock.remaining_ms() == pytest.approx(500.0)
    assert lock.held()
    ticker.seconds = 1.0
    assert not lock.held()
    assert lock.remaining_ms() == 0.0
    ticker.seconds = 0.0
    redis_client.set("k", "somebody-else")
    assert not lock.held()


# --- finding 3: atomic train-or-reuse ----------------------------------------------------------


class _SlowStates(DetectorStateStore):
    """Makes every reader wait at a barrier so unsynchronised callers overlap."""

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


def test_concurrent_scoring_of_one_metric_trains_exactly_once(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    metric = _seed_ref(owner)
    states = _SlowStates(writer_engine)
    states.barrier = threading.Barrier(2)
    scorer = MetricScorer(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=states,
        clock=Clock(),
    )
    trained: list[int] = []
    real_train = isolation_forest.train

    def counting_train(
        frame: object, *, trained_at: datetime, version: int
    ) -> isolation_forest.TrainedForest:
        trained.append(version)
        return real_train(frame, trained_at=trained_at, version=version)  # type: ignore[arg-type]

    monkeypatch.setattr(isolation_forest, "train", counting_train)
    start = threading.Barrier(2)
    errors: list[BaseException] = []

    def work() -> None:
        try:
            start.wait(timeout=5)
            scorer.score_metric(metric)
        except BaseException as error:  # noqa: BLE001
            errors.append(error)

    threads = [threading.Thread(target=work) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=60)

    assert errors == []
    assert trained == [1]
    assert _rows(owner, "SELECT version FROM detector_state") == [(1,)]


def test_state_writes_are_compare_and_set_and_never_move_the_version_backwards(
    owner: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner, seasonal(80)).id
    store = DetectorStateStore(writer_engine)
    when = NOW - timedelta(hours=1)

    def state(version: int, points: int) -> isolation_forest.ForestState:
        return isolation_forest.ForestState(
            trained_at=when, version=version, contamination=0.02, point_count=points
        )

    detector = Detector.ISOLATION_FOREST
    assert store.compare_and_set(metric, detector, state(3, 300), expected_version=None)
    # a second inserter based on "no row" lost
    assert not store.compare_and_set(metric, detector, state(1, 100), expected_version=None)
    # writers that read an older version than the stored one lost as well
    assert not store.compare_and_set(metric, detector, state(2, 200), expected_version=1)
    assert not store.compare_and_set(metric, detector, state(3, 999), expected_version=2)
    assert store.compare_and_set(metric, detector, state(4, 400), expected_version=3)
    assert not store.compare_and_set(metric, detector, state(4, 401), expected_version=3)

    stored = store.get(metric, Detector.ISOLATION_FOREST)
    assert stored is not None
    assert (stored.version, stored.point_count) == (4, 400)


def test_a_process_without_the_persisted_model_refits_locally_at_the_persisted_version(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner)
    _scorer(reader_engine, writer_engine).score_metric(metric)
    persisted = _rows(owner, "SELECT version, trained_at FROM detector_state")
    other_worker = _scorer(reader_engine, writer_engine)

    other_worker.score_metric(metric)

    assert _rows(owner, "SELECT version, trained_at FROM detector_state") == persisted
    assert other_worker._forests[metric.id].state.version == 1


def test_a_stale_cached_model_is_replaced_when_another_worker_persisted_a_newer_version(
    owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner)
    slow = _scorer(reader_engine, writer_engine)
    slow.score_metric(metric)
    with owner.begin() as conn:
        conn.execute(
            text(
                "UPDATE detector_state SET version = 5, "
                "params = jsonb_set(jsonb_set(params, '{version}', '5'), "
                "'{data_hash}', '\"other\"')"
            )
        )

    slow.score_metric(metric)

    # another snapshot of record: this process refits locally, carrying but not claiming v5
    assert slow._forests[metric.id].state.version == 5
    assert slow._forests[metric.id].local
    assert _rows(owner, "SELECT version FROM detector_state") == [(5,)]


# --- findings 5 and 6: window bounds and downsampling -----------------------------------------


def test_future_points_do_not_activate_a_metric_or_enter_the_window(
    owner: Engine, reader_engine: Engine
) -> None:
    _, only_future = seed_metric(owner, [1.0] * 10, end=NOW + timedelta(hours=3))
    _, mixed = seed_metric(owner, [1.0] * 10, end=NOW - timedelta(minutes=5))
    add_points(owner, mixed, [1000.0] * 5, end=NOW + timedelta(hours=2))
    reader = MetricReader(reader_engine)

    active = {m.id for m in reader.active_metrics(NOW - timedelta(hours=24), NOW)}
    frame = reader.load_series(mixed, NOW - timedelta(hours=24), NOW)

    assert only_future not in active
    assert mixed in active
    assert len(frame) == 10
    assert frame["value"].max() == 1.0
    assert len(reader.load_series(only_future, NOW - timedelta(hours=24), NOW)) == 0


def test_future_points_produce_no_training_and_no_anomalies(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky(), end=NOW + timedelta(hours=26))

    summary = _run_all(reader_engine, _scorer(reader_engine, writer_engine), redis_client)

    assert summary.metrics == 0
    assert _rows(owner, "SELECT 1 FROM anomaly") == []
    assert _rows(owner, "SELECT 1 FROM detector_state") == []


def test_a_point_exactly_at_now_is_included(owner: Engine, reader_engine: Engine) -> None:
    _, metric = seed_metric(owner, [1.0, 2.0, 3.0], end=NOW)

    frame = MetricReader(reader_engine).load_series(metric, NOW - timedelta(hours=24), NOW)

    assert len(frame) == 3


def test_a_late_spike_behind_the_old_20000_point_cutoff_is_detected(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    total = 21_000  # one point every four seconds for 23 hours 20 minutes
    rng = np.random.default_rng(3)
    values = 10.0 + rng.normal(0.0, 0.5, total)
    spike_index = 500  # behind the newest 20 000 points
    values[spike_index] += 40.0
    metric = _seed_ref(owner, np.zeros(1))
    end = NOW - timedelta(seconds=10)
    first = end - timedelta(seconds=4 * (total - 1))
    rows = [
        {"m": metric.id, "ts": first + timedelta(seconds=4 * i), "v": float(v)}
        for i, v in enumerate(values)
    ]
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM metric_point"))
        conn.execute(
            text("INSERT INTO metric_point (metric_id, ts, value) VALUES (:m, :ts, :v)"), rows
        )
    spike_ts = first + timedelta(seconds=4 * spike_index)
    reader = MetricReader(reader_engine)

    frame = reader.load_series(metric.id, NOW - timedelta(hours=24), NOW)
    summary = _run_all(reader_engine, _scorer(reader_engine, writer_engine), redis_client)

    assert 0 < len(frame) <= 20_000
    assert frame["ts"].iloc[0].to_pydatetime() <= first + timedelta(seconds=5)
    assert reader.downsampled_windows == 1
    assert summary.inserted >= 1
    flagged = _rows(owner, "SELECT window_start FROM anomaly WHERE detector = 'ZSCORE'")
    assert any(abs((row[0] - spike_ts).total_seconds()) <= 5 for row in flagged)  # type: ignore[operator]


def test_small_windows_are_not_downsampled(owner: Engine, reader_engine: Engine) -> None:
    _, metric = seed_metric(owner, seasonal(100))
    reader = MetricReader(reader_engine, max_points=100)

    frame = reader.load_series(metric, NOW - timedelta(hours=24), NOW)

    assert len(frame) == 100
    assert reader.downsampled_windows == 0


def test_downsampling_is_deterministic_and_covers_the_whole_window(
    owner: Engine, reader_engine: Engine
) -> None:
    _, metric = seed_metric(owner, seasonal(280))
    reader = MetricReader(reader_engine, max_points=100)

    first = reader.load_series(metric, NOW - timedelta(hours=24), NOW)
    second = reader.load_series(metric, NOW - timedelta(hours=24), NOW)

    assert first.equals(second)
    assert 0 < len(first) <= 100
    oldest = _rows(owner, "SELECT min(ts) FROM metric_point")[0][0]
    # the first bucket is represented by one of its own raw points
    first_ts = first["ts"].iloc[0].to_pydatetime()
    assert oldest <= first_ts <= oldest + timedelta(seconds=873)  # type: ignore[operator]
    assert reader.downsampled_windows == 2


# --- finding 7: detector isolation ---------------------------------------------------------------


def test_a_failing_forest_does_not_discard_the_zscore_results(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    metric = _seed_ref(owner)

    def broken(*args: object, **kwargs: object) -> isolation_forest.TrainedForest:
        raise isolation_forest.StandardizationError("cannot scale")

    monkeypatch.setattr(isolation_forest, "train", broken)

    outcome = _scorer(reader_engine, writer_engine).score_metric(metric)

    assert outcome.inserted
    assert {r.detector for r in outcome.results} == {Detector.ZSCORE}
    assert _rows(owner, "SELECT DISTINCT detector FROM anomaly") == [("ZSCORE",)]


def test_a_failing_zscore_does_not_discard_the_forest_results(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    metric = _seed_ref(owner)

    def broken(*args: object, **kwargs: object) -> list[object]:
        raise RuntimeError("boom")

    monkeypatch.setattr(zscore, "detect", broken)

    outcome = _scorer(reader_engine, writer_engine).score_metric(metric)

    assert {r.detector for r in outcome.results} == {Detector.ISOLATION_FOREST}


@pytest.mark.parametrize("magnitude", [1e40, 1e100])
def test_huge_magnitudes_are_stored_end_to_end(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, magnitude: float
) -> None:
    metric = _seed_ref(owner, _spiky() * magnitude)

    outcome = _scorer(reader_engine, writer_engine).score_metric(metric)

    assert {r.detector for r in outcome.results} == {Detector.ZSCORE, Detector.ISOLATION_FOREST}
    assert json.dumps([float(r.score) for r in outcome.results])
