"""Scoring pipeline: persistence, idempotency, retrain state, locking and fault isolation."""

import threading
from datetime import UTC, datetime, timedelta

import numpy as np
from redis import Redis
from sqlalchemy import Engine, text

from pulseboard_analytics.clients.api_core import ApiCoreClient, NotifyResult
from pulseboard_analytics.jobs.score_all import LOCK_KEY, RunSummary, score_all_metrics
from pulseboard_analytics.jobs.scoring import MetricScorer, ScoreOutcome
from pulseboard_analytics.locks import RedisLock
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from tests.conftest import NOW, add_points, seed_metric
from tests.support.fake_server import fake_api_core
from tests.support.series import seasonal

LOCK_TTL_MS = 30_000


class RecordingNotifier:
    """Collects what the job would send to api-core."""

    def __init__(self) -> None:
        self.calls: list[list[StoredAnomaly]] = []

    def notify(self, anomalies: list[StoredAnomaly]) -> NotifyResult:
        self.calls.append(list(anomalies))
        return NotifyResult(delivered=len(anomalies), failed=0)


class Clock:
    """A settable clock."""

    def __init__(self, now: datetime = NOW) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now


def _spiky(count: int = 288, at: int = 250, size: float = 80.0) -> np.ndarray:
    values = seasonal(count)
    values[at] += size
    return values


def _scorer(
    reader_engine: Engine,
    writer_engine: Engine,
    clock: Clock | None = None,
    cls: type[MetricScorer] = MetricScorer,
) -> MetricScorer:
    return cls(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=clock or Clock(),
    )


def _run(
    reader_engine: Engine,
    scorer: MetricScorer,
    notifier: RecordingNotifier | ApiCoreClient,
    redis: Redis,
) -> RunSummary:
    return score_all_metrics(
        reader=MetricReader(reader_engine),
        scorer=scorer,
        notifier=notifier,
        redis=redis,
        lock_ttl_ms=LOCK_TTL_MS,
    )


def _rows(owner: Engine, sql: str) -> list[tuple[object, ...]]:
    with owner.connect() as conn:
        return [tuple(row) for row in conn.execute(text(sql)).all()]


def test_first_run_stores_and_notifies_and_second_run_does_neither(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    tenant, metric = seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine)
    notifier = RecordingNotifier()

    first = _run(reader_engine, scorer, notifier, redis_client)
    second = _run(reader_engine, scorer, notifier, redis_client)

    assert first.metrics == 1
    assert first.inserted >= 1
    assert first.notified == first.inserted
    assert len(notifier.calls) == 1
    assert second.inserted == 0
    assert second.notified == 0
    assert len(notifier.calls) == 1
    stored = _rows(owner, "SELECT detector, severity, tenant_id FROM anomaly")
    assert len(stored) == first.inserted
    assert ("ZSCORE", "HIGH", tenant) in stored
    assert {row[2] for row in stored} == {tenant}
    stored_ids = {row[0] for row in _rows(owner, "SELECT id FROM anomaly")}
    assert {a.id for a in notifier.calls[0]} == stored_ids
    assert metric in {row[0] for row in _rows(owner, "SELECT metric_id FROM anomaly")}


def test_a_fresh_scorer_does_not_duplicate_what_a_previous_one_stored(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    notifier = RecordingNotifier()

    first = _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)
    restarted = _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)

    assert first.inserted >= 1
    assert restarted.inserted == 0
    assert len(notifier.calls) == 1


def test_late_point_is_scored_on_the_next_run(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    values = seasonal(288)
    _, metric = seed_metric(owner, values)
    late_ts = NOW - timedelta(minutes=5 * (288 - 250))
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM metric_point WHERE ts = :ts"), {"ts": late_ts})
    scorer = _scorer(reader_engine, writer_engine)
    notifier = RecordingNotifier()
    _run(reader_engine, scorer, notifier, redis_client)
    before = _rows(owner, "SELECT 1 FROM anomaly WHERE detector = 'ZSCORE'")

    add_points(owner, metric, [values[250] + 80.0], end=late_ts)
    second = _run(reader_engine, scorer, notifier, redis_client)

    assert before == []
    assert second.inserted >= 1
    zscore_windows = _rows(owner, "SELECT window_start FROM anomaly WHERE detector = 'ZSCORE'")
    assert (late_ts,) in zscore_windows


def test_flat_and_short_metrics_produce_nothing(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, [5.0] * 288)
    seed_metric(owner, [1.0, 2.0, 100.0])
    notifier = RecordingNotifier()

    summary = _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)

    assert summary.metrics == 2
    assert summary.inserted == 0
    assert notifier.calls == []
    assert _rows(owner, "SELECT 1 FROM detector_state") == []


def test_no_active_metric_is_an_empty_run(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, seasonal(40), end=NOW - timedelta(days=2))
    notifier = RecordingNotifier()

    summary = _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)

    assert summary == RunSummary(metrics=0)
    assert notifier.calls == []


def test_model_state_is_persisted_with_the_documented_params(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    _, metric = seed_metric(owner, _spiky())

    _run(reader_engine, _scorer(reader_engine, writer_engine), RecordingNotifier(), redis_client)

    ((detector, params, trained_at, version),) = _rows(
        owner, "SELECT detector, params, trained_at, version FROM detector_state"
    )
    assert detector == "ISOLATION_FOREST"
    assert version == 1
    assert trained_at == NOW
    assert isinstance(params, dict)
    assert params["version"] == 1
    assert params["contamination"] == 0.02
    assert params["point_count"] == 288
    assert datetime.fromisoformat(str(params["trained_at"])) == NOW
    assert _rows(owner, "SELECT metric_id FROM detector_state") == [(metric,)]


def test_forest_is_kept_until_six_hours_have_passed(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    clock = Clock()
    scorer = _scorer(reader_engine, writer_engine, clock)
    notifier = RecordingNotifier()

    _run(reader_engine, scorer, notifier, redis_client)
    clock.now = NOW + timedelta(hours=5, minutes=59)
    _run(reader_engine, scorer, notifier, redis_client)
    unchanged = _rows(owner, "SELECT version, trained_at FROM detector_state")
    clock.now = NOW + timedelta(hours=6)
    _run(reader_engine, scorer, notifier, redis_client)
    retrained = _rows(owner, "SELECT version, trained_at FROM detector_state")

    assert unchanged == [(1, NOW)]
    assert retrained == [(2, NOW + timedelta(hours=6))]


def test_forest_is_retrained_when_the_window_grew_by_twenty_percent(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    values = seasonal(130)
    _, metric = seed_metric(owner, values[:100], end=NOW - timedelta(minutes=5 * 31))
    scorer = _scorer(reader_engine, writer_engine)
    notifier = RecordingNotifier()
    _run(reader_engine, scorer, notifier, redis_client)

    add_points(owner, metric, values[100:119], end=NOW - timedelta(minutes=5 * 12))
    _run(reader_engine, scorer, notifier, redis_client)
    before = _rows(owner, "SELECT version, params FROM detector_state")
    add_points(owner, metric, values[119:], end=NOW - timedelta(minutes=5))
    _run(reader_engine, scorer, notifier, redis_client)
    after = _rows(owner, "SELECT version, params FROM detector_state")

    assert before[0][0] == 1
    assert after[0][0] == 2
    assert isinstance(after[0][1], dict)
    assert after[0][1]["point_count"] == 130


def test_restart_without_the_in_memory_model_refits_without_bumping_the_version(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    notifier = RecordingNotifier()
    _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)

    _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)

    assert _rows(owner, "SELECT version FROM detector_state") == [(1,)]


class _FailsFor(MetricScorer):
    """Raises for one metric, scores the rest normally."""

    broken: MetricRef | None = None

    def score_metric(self, metric: MetricRef) -> ScoreOutcome:
        if metric == self.broken:
            raise RuntimeError("boom")
        return super().score_metric(metric)


def test_one_failing_metric_does_not_stop_the_others(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    tenant_a, broken_id = seed_metric(owner, _spiky())
    tenant_b, healthy_id = seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine, cls=_FailsFor)
    assert isinstance(scorer, _FailsFor)
    scorer.broken = MetricRef(id=broken_id, tenant_id=tenant_a)
    notifier = RecordingNotifier()

    summary = _run(reader_engine, scorer, notifier, redis_client)

    assert summary.metrics == 2
    assert summary.failed_metrics == 1
    assert summary.inserted >= 1
    stored = _rows(owner, "SELECT DISTINCT metric_id, tenant_id FROM anomaly")
    assert stored == [(healthy_id, tenant_b)]
    assert redis_client.get(LOCK_KEY) is None


def test_failing_notification_keeps_the_stored_anomalies(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    with fake_api_core([500]) as server:
        client = ApiCoreClient(server.url, "z" * 40, sleep=lambda _: None)
        summary = _run(reader_engine, _scorer(reader_engine, writer_engine), client, redis_client)
        client.close()

    assert summary.inserted >= 1
    assert summary.notified == 0
    assert len(server.requests) == 3
    assert len(_rows(owner, "SELECT 1 FROM anomaly")) == summary.inserted
    assert redis_client.get(LOCK_KEY) is None


class _BlockingScorer(MetricScorer):
    """Signals when scoring starts and waits until released."""

    started: threading.Event
    release: threading.Event

    def score_metric(self, metric: MetricRef) -> ScoreOutcome:
        self.started.set()
        assert self.release.wait(timeout=10)
        return ScoreOutcome(results=[], inserted=[])


def test_only_one_of_two_concurrent_runs_executes(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine, cls=_BlockingScorer)
    assert isinstance(scorer, _BlockingScorer)
    scorer.started = threading.Event()
    scorer.release = threading.Event()
    results: list[RunSummary] = []
    runner = threading.Thread(
        target=lambda: results.append(
            _run(reader_engine, scorer, RecordingNotifier(), redis_client)
        )
    )
    runner.start()
    try:
        assert scorer.started.wait(timeout=10)

        concurrent = _run(reader_engine, scorer, RecordingNotifier(), redis_client)
        assert redis_client.get(LOCK_KEY) is not None
    finally:
        scorer.release.set()
        runner.join(timeout=10)

    assert concurrent == RunSummary(skipped=True)
    assert [r.skipped for r in results] == [False]
    assert results[0].metrics == 1
    assert redis_client.get(LOCK_KEY) is None


def test_run_is_skipped_while_another_instance_holds_the_lock(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    other = RedisLock(redis_client, LOCK_KEY, 60_000)
    assert other.acquire()
    notifier = RecordingNotifier()

    summary = _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)

    assert summary.skipped
    assert notifier.calls == []
    assert _rows(owner, "SELECT 1 FROM anomaly") == []
    assert redis_client.get(LOCK_KEY) is not None
    assert other.release()


def test_run_proceeds_once_the_previous_lock_expired(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    assert redis_client.set(LOCK_KEY, "dead-instance", px=1)
    deadline = datetime.now(UTC) + timedelta(seconds=5)
    while redis_client.get(LOCK_KEY) is not None:
        assert datetime.now(UTC) < deadline, "lock did not expire"

    summary = _run(
        reader_engine, _scorer(reader_engine, writer_engine), RecordingNotifier(), redis_client
    )

    assert not summary.skipped
    assert summary.inserted >= 1
