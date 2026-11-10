"""Fair scheduling, lease-bounded work, database timeouts and notify budgets."""

import socket
import threading
import time
import uuid

import pytest
from redis import Redis
from sqlalchemy import Engine, event, text
from sqlalchemy.exc import DBAPIError

from pulseboard_analytics.budget import Budget, BudgetExhausted
from pulseboard_analytics.clients.api_core import ApiCoreClient, NotifyResult
from pulseboard_analytics.detectors import isolation_forest
from pulseboard_analytics.detectors.base import Detector
from pulseboard_analytics.jobs.score_all import (
    CURSOR_KEY,
    LOCK_KEY,
    NOTIFY_BATCH,
    RunSummary,
    score_all_metrics,
)
from pulseboard_analytics.jobs.scoring import MetricScorer, ScoreOutcome
from pulseboard_analytics.locks import RedisLock
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.timeouts import apply_budget
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from tests.conftest import NOW
from tests.support.fake_server import fake_api_core
from tests.test_scoring_limits import (
    LOCK_TTL_MS,
    Clock,
    Recorder,
    Ticker,
    _fake_anomaly,
    _seed_ref,
)

MARGIN = 5.0


def _run(
    reader_engine: Engine,
    scorer: MetricScorer,
    redis: Redis,
    ticker: Ticker,
    notifier: object | None = None,
    **extra: float,
) -> RunSummary:
    return score_all_metrics(
        reader=MetricReader(reader_engine),
        scorer=scorer,
        notifier=notifier if notifier is not None else Recorder(),  # type: ignore[arg-type]
        redis=redis,
        lock_ttl_ms=LOCK_TTL_MS,
        monotonic=ticker,
        **extra,
    )


class _Slice(MetricScorer):
    """Scores nothing but spends ``cost`` seconds of the run's clock per metric."""

    seen: list[uuid.UUID]
    ticker: Ticker
    cost: float

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        self.seen.append(metric.id)
        self.ticker.seconds += self.cost
        return ScoreOutcome(results=[], inserted=[])


def _slice(reader_engine: Engine, writer_engine: Engine, ticker: Ticker, cost: float) -> _Slice:
    scorer = _Slice(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )
    scorer.seen = []
    scorer.ticker = ticker
    scorer.cost = cost
    return scorer


# --- fair scheduling ---------------------------------------------------------------------------


def test_three_consecutive_deadline_limited_runs_score_every_metric(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    ids = {_seed_ref(owner).id for _ in range(3)}
    scored: list[uuid.UUID] = []

    for _ in range(3):
        ticker = Ticker()  # every process restart gets a fresh clock and a fresh scorer
        scorer = _slice(reader_engine, writer_engine, ticker, cost=50.0)  # one metric per run
        _run(reader_engine, scorer, redis_client, ticker)
        assert len(scorer.seen) == 1
        scored.extend(scorer.seen)

    assert set(scored) == ids
    assert len(scored) == 3  # no metric was visited twice before every metric was visited once


def test_a_bounded_number_of_runs_covers_all_metrics_when_several_fit_per_run(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    ids = {_seed_ref(owner).id for _ in range(7)}
    scored: set[uuid.UUID] = set()

    runs = 0
    while scored != ids and runs < 10:
        ticker = Ticker()
        scorer = _slice(reader_engine, writer_engine, ticker, cost=20.0)  # 3 metrics per run
        _run(reader_engine, scorer, redis_client, ticker)
        scored.update(scorer.seen)
        runs += 1

    assert scored == ids
    assert runs <= 3  # ceil(7 / 3)


def test_a_run_that_finishes_clears_the_cursor_and_starts_over(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    cut = _slice(reader_engine, writer_engine, ticker, cost=50.0)
    _run(reader_engine, cut, redis_client, ticker)
    assert redis_client.get(CURSOR_KEY) is not None

    full_ticker = Ticker()
    full = _slice(reader_engine, writer_engine, full_ticker, cost=0.0)
    summary = _run(reader_engine, full, redis_client, full_ticker)

    assert summary.unscored_metrics == 0
    assert len(full.seen) == 3
    assert set(full.seen) >= set(cut.seen)
    assert redis_client.get(CURSOR_KEY) is None


def test_an_unreadable_cursor_is_ignored(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    _seed_ref(owner)
    redis_client.set(CURSOR_KEY, "not-a-uuid")
    ticker = Ticker()
    scorer = _slice(reader_engine, writer_engine, ticker, cost=0.0)

    summary = _run(reader_engine, scorer, redis_client, ticker)

    assert summary.metrics == 1
    assert len(scorer.seen) == 1


# --- lease: nothing protected runs after the lease end -----------------------------------------


class _TimedWrites:
    """Records the (fake) time of every protected write and the budget it was given."""

    def __init__(self, ticker: Ticker) -> None:
        self.ticker = ticker
        self.calls: list[tuple[str, float, float | None]] = []

    def record(self, kind: str, budget: Budget | None) -> None:
        left = None if budget is None else budget.remaining_seconds()
        self.calls.append((kind, self.ticker.seconds, left))


class _TimedAnomalies(AnomalyWriter):
    log: _TimedWrites

    def insert_new(self, metric, results, budget=None):  # type: ignore[no-untyped-def]
        self.log.record("insert", budget)
        return super().insert_new(metric, results, budget)


class _TimedStates(DetectorStateStore):
    log: _TimedWrites

    def compare_and_set(self, *args, budget=None, **kwargs):  # type: ignore[no-untyped-def]
        self.log.record("state", budget)
        return super().compare_and_set(*args, budget=budget, **kwargs)


class _TimedNotifier:
    def __init__(self, log: _TimedWrites) -> None:
        self.log = log

    def notify(self, anomalies: list[StoredAnomaly], budget: Budget | None = None) -> NotifyResult:
        self.log.record("notify", budget)
        return NotifyResult(delivered=len(anomalies), failed=0)


def _timed_scorer(reader_engine: Engine, writer_engine: Engine, log: _TimedWrites) -> MetricScorer:
    anomalies = _TimedAnomalies(writer_engine)
    anomalies.log = log
    states = _TimedStates(writer_engine)
    states.log = log
    return MetricScorer(
        reader=MetricReader(reader_engine), anomalies=anomalies, states=states, clock=Clock()
    )


def test_every_protected_write_of_a_run_ends_before_the_lease_ends(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    log = _TimedWrites(ticker)

    _run(
        reader_engine,
        _timed_scorer(reader_engine, writer_engine, log),
        redis_client,
        ticker,
        _TimedNotifier(log),
    )

    kinds = {kind for kind, _, _ in log.calls}
    assert kinds == {"insert", "state", "notify"}
    lease = LOCK_TTL_MS / 1000.0
    for kind, at, left in log.calls:
        assert left is not None, kind
        assert at + left <= lease + 1e-6, (kind, at, left)


def test_training_that_outlasts_the_lease_writes_nothing_and_stops_the_run(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    redis_client: Redis,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A is training when its lease ends and B takes over: A must not touch the database."""
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    log = _TimedWrites(ticker)
    real = isolation_forest.train

    def slow_train(*args: object, **kwargs: object) -> isolation_forest.TrainedForest:
        forest = real(*args, **kwargs)  # type: ignore[arg-type]
        ticker.seconds = LOCK_TTL_MS / 1000.0 + 1.0  # the lease ran out during training
        redis_client.delete(LOCK_KEY)  # and B acquired it
        assert RedisLock(redis_client, LOCK_KEY, LOCK_TTL_MS).acquire()
        return forest

    monkeypatch.setattr(isolation_forest, "train", slow_train)

    summary = _run(
        reader_engine,
        _timed_scorer(reader_engine, writer_engine, log),
        redis_client,
        ticker,
        _TimedNotifier(log),
    )

    assert [kind for kind, _, _ in log.calls] == []  # no state write, no insert, no notify
    assert summary.failed_metrics == 1
    assert summary.unscored_metrics == 3
    with owner.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM detector_state")).scalar_one() == 0
        assert conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one() == 0
    # the next run resumes with the metric that was cut off
    assert redis_client.get(CURSOR_KEY) is not None
    assert redis_client.get(LOCK_KEY) is not None  # B's lease was not released by A


def test_no_new_metric_starts_inside_the_safety_margin(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    # the first metric ends 51 s into a 55 s lease: 4 s are left, less than the 5 s margin
    scorer = _slice(reader_engine, writer_engine, ticker, cost=51.0)

    summary = _run(reader_engine, scorer, redis_client, ticker, deadline_seconds=1000.0)

    assert len(scorer.seen) == 1
    assert summary.unscored_metrics == 2


def test_the_lease_is_rechecked_after_the_redis_round_trip(redis_client: Redis) -> None:
    ticker = Ticker()

    class SlowGet:
        """A Redis whose GET answers correctly but only after the lease has run out."""

        def __init__(self, inner: Redis) -> None:
            self._inner = inner

        def get(self, key: str) -> object:
            value = self._inner.get(key)
            ticker.seconds = 56.0
            return value

        def __getattr__(self, name: str) -> object:
            return getattr(self._inner, name)

    lock = RedisLock(SlowGet(redis_client), "slow", LOCK_TTL_MS, ticker)  # type: ignore[arg-type]
    assert lock.acquire()
    ticker.seconds = 54.9

    assert not lock.held()
    assert lock.expires_at() == pytest.approx(55.0)


# --- database timeouts derived from the budget ---------------------------------------------------


def test_the_budget_sets_statement_and_lock_timeouts_for_the_transaction(
    writer_engine: Engine,
) -> None:
    with writer_engine.begin() as conn:
        apply_budget(conn, Budget(time.monotonic() + 2.5))
        statement = conn.execute(text("SELECT current_setting('statement_timeout')")).scalar_one()
        lock = conn.execute(text("SELECT current_setting('lock_timeout')")).scalar_one()
    for value in (statement, lock):
        assert value.endswith("ms") or value.endswith("s")
    assert int(statement.removesuffix("ms")) <= 2500
    assert int(lock.removesuffix("ms")) <= 2500
    with writer_engine.begin() as conn:  # SET LOCAL: the next transaction is unaffected
        assert conn.execute(text("SELECT current_setting('statement_timeout')")).scalar_one() == "0"


def test_a_spent_budget_stops_before_touching_the_database(writer_engine: Engine) -> None:
    statements: list[str] = []

    @event.listens_for(writer_engine, "before_cursor_execute")
    def record(conn, cursor, statement, parameters, context, executemany):  # type: ignore[no-untyped-def]
        statements.append(statement)

    try:
        with pytest.raises(BudgetExhausted), writer_engine.begin() as conn:
            apply_budget(conn, Budget(time.monotonic() - 1.0))
    finally:
        event.remove(writer_engine, "before_cursor_execute", record)

    assert statements == []


def test_a_state_write_blocked_on_a_row_lock_gives_up_with_the_budget(
    owner: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner)
    store = DetectorStateStore(writer_engine)
    first = isolation_forest.ForestState(
        trained_at=NOW, version=1, contamination=0.02, point_count=10, data_hash="a"
    )
    second = isolation_forest.ForestState(
        trained_at=NOW, version=2, contamination=0.02, point_count=10, data_hash="b"
    )
    assert store.compare_and_set(metric.id, Detector.ISOLATION_FOREST, first, expected_version=None)

    with owner.connect() as holder:
        holder.execute(text("UPDATE detector_state SET version = version"))  # row lock, open txn
        started = time.monotonic()
        with pytest.raises(DBAPIError):
            store.compare_and_set(
                metric.id,
                Detector.ISOLATION_FOREST,
                second,
                expected_version=1,
                budget=Budget(time.monotonic() + 0.4),
            )
        waited = time.monotonic() - started
        holder.rollback()

    assert 0.3 <= waited < 5.0


# --- notify budget -----------------------------------------------------------------------------


def _hanging_server() -> tuple[str, threading.Event, list[socket.socket]]:
    """Accepts connections and never answers; returns its URL, a stop flag and open sockets."""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(8)
    server.settimeout(0.2)
    stop = threading.Event()
    held: list[socket.socket] = []

    def serve() -> None:
        while not stop.is_set():
            try:
                connection, _ = server.accept()
            except OSError:
                continue
            held.append(connection)
        server.close()

    threading.Thread(target=serve, daemon=True).start()
    return f"http://127.0.0.1:{server.getsockname()[1]}", stop, held


def test_the_notify_client_never_outlasts_its_budget() -> None:
    url, stop, held = _hanging_server()
    sleeps: list[float] = []
    client = ApiCoreClient(url, "n" * 40, timeout=30.0, sleep=sleeps.append)
    try:
        started = time.monotonic()
        result = client.notify([_fake_anomaly(_ref()) for _ in range(3)], Budget(started + 0.6))
        elapsed = time.monotonic() - started
    finally:
        stop.set()
        client.close()
        for connection in held:
            connection.close()

    assert (result.delivered, result.failed) == (0, 3)
    assert elapsed < 3.0  # the 30 s client timeout and the retries were cut to the budget


def test_a_spent_budget_sends_nothing() -> None:
    with fake_api_core() as server:
        client = ApiCoreClient(server.url, "n" * 40, timeout=2.0, sleep=lambda _: None)
        result = client.notify([_fake_anomaly(_ref())], Budget(time.monotonic() - 1.0))
        client.close()

    assert (result.delivered, result.failed) == (0, 1)
    assert server.requests == []


def _ref() -> MetricRef:
    return MetricRef(id=uuid.uuid4(), tenant_id=uuid.uuid4())


# --- delivery in batches (first batch fails, one metric above one batch) --------------------------


class _FailsFirst:
    """Raises on the first call, accepts every later one."""

    def __init__(self) -> None:
        self.calls: list[list[StoredAnomaly]] = []

    def notify(self, anomalies: list[StoredAnomaly], budget: Budget | None = None) -> NotifyResult:
        self.calls.append(list(anomalies))
        if len(self.calls) == 1:
            raise RuntimeError("api-core down")
        return NotifyResult(delivered=len(anomalies), failed=0)


class _Many(MetricScorer):
    count: int = 0

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        return ScoreOutcome(results=[], inserted=[_fake_anomaly(metric) for _ in range(self.count)])


def _many(reader_engine: Engine, writer_engine: Engine, count: int) -> _Many:
    scorer = _Many(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )
    scorer.count = count
    return scorer


def test_a_failed_first_batch_does_not_stop_the_next_batches(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(2):
        _seed_ref(owner)
    notifier = _FailsFirst()

    summary = _run(
        reader_engine, _many(reader_engine, writer_engine, 600), redis_client, Ticker(), notifier
    )

    assert [len(call) for call in notifier.calls] == [NOTIFY_BATCH, NOTIFY_BATCH, 200]
    assert summary.inserted == 1200
    assert summary.notified == 700  # the first batch of 500 was lost, the others delivered
    assert summary.failed_metrics == 0


def test_one_metric_with_more_results_than_a_batch_is_split_into_batches(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    _seed_ref(owner)
    notifier = Recorder()

    summary = _run(
        reader_engine, _many(reader_engine, writer_engine, 1200), redis_client, Ticker(), notifier
    )

    assert [len(call) for call in notifier.calls] == [NOTIFY_BATCH, NOTIFY_BATCH, 200]
    assert summary.inserted == summary.notified == 1200
    ids = [a.id for call in notifier.calls for a in call]
    assert len(set(ids)) == 1200
