"""Slow-metric backoff, owner-guarded cursor writes, hard notify deadline, bounded checkout."""

import socket
import threading
import time
import uuid
from datetime import timedelta

import numpy as np
import pytest
from redis import Redis
from sqlalchemy import Engine, event, text

from pulseboard_analytics.budget import Budget, BudgetExhausted
from pulseboard_analytics.clients.api_core import ApiCoreClient
from pulseboard_analytics.detectors import zscore
from pulseboard_analytics.detectors.base import Detector, DetectorResult, Severity
from pulseboard_analytics.detectors.isolation_forest import ForestState
from pulseboard_analytics.jobs.score_all import (
    CURSOR_KEY,
    LOCK_KEY,
    SLOW_BASE_BACKOFF_SECONDS,
    SLOW_KEY,
    score_all_metrics,
)
from pulseboard_analytics.jobs.scoring import MetricScorer, ScoreOutcome
from pulseboard_analytics.locks import RedisLock
from pulseboard_analytics.repositories.connections import PoolBusyError, connect
from pulseboard_analytics.repositories.engines import make_engine
from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import (
    INSERT_CHUNK,
    AnomalyWriter,
    DetectorStateStore,
)
from tests.conftest import NOW
from tests.support.series import make_frame, noisy
from tests.test_scoring_limits import LOCK_TTL_MS, Clock, Recorder, Ticker, _fake_anomaly, _seed_ref

B_CURSOR = "00000000-0000-4000-8000-00000000b0b0"


class _Wall:
    def __init__(self) -> None:
        self.seconds = 1_000_000.0

    def __call__(self) -> float:
        return self.seconds


class _Scorer(MetricScorer):
    """Spends ``cost`` seconds per metric; metrics in ``slow`` exhaust their budget."""

    seen: list[uuid.UUID]
    ticker: Ticker
    cost: float
    slow: set[uuid.UUID]
    hook: object

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        self.seen.append(metric.id)
        self.ticker.seconds += self.cost
        if metric.id in self.slow:
            raise BudgetExhausted("always too slow")
        return ScoreOutcome(results=[], inserted=[])


def _scorer(
    reader_engine: Engine,
    writer_engine: Engine,
    ticker: Ticker,
    cost: float,
    slow: set[uuid.UUID] | None = None,
) -> _Scorer:
    scorer = _Scorer(
        reader=MetricReader(reader_engine),
        anomalies=AnomalyWriter(writer_engine),
        states=DetectorStateStore(writer_engine),
        clock=Clock(),
    )
    scorer.seen = []
    scorer.ticker = ticker
    scorer.cost = cost
    scorer.slow = slow or set()
    return scorer


def _run(
    reader_engine: Engine,
    scorer: MetricScorer,
    redis: Redis,
    ticker: Ticker,
    wall: _Wall | None = None,
) -> object:
    return score_all_metrics(
        reader=MetricReader(reader_engine),
        scorer=scorer,
        notifier=Recorder(),
        redis=redis,
        lock_ttl_ms=LOCK_TTL_MS,
        monotonic=ticker,
        wall=wall or _Wall(),
    )


# --- 1. a metric that always exhausts its budget cannot starve the others ------------------------


def test_a_metric_that_always_exhausts_its_budget_cannot_starve_the_others(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    ids = sorted(_seed_ref(owner).id for _ in range(4))
    slow_id = ids[0]  # first in id order: the head of the line
    wall = _Wall()
    seen_per_run: list[list[uuid.UUID]] = []

    for _ in range(4):  # every run scores one metric (cost 50 s of the 50 s deadline)
        ticker = Ticker()
        scorer = _scorer(reader_engine, writer_engine, ticker, cost=50.0, slow={slow_id})
        _run(reader_engine, scorer, redis_client, ticker, wall)
        seen_per_run.append(scorer.seen)

    flat = [metric for run in seen_per_run for metric in run]
    # run 1 tried the slow metric and moved on; the next runs covered every other metric
    assert seen_per_run[0] == [slow_id]
    assert set(flat[1:]) == set(ids[1:])
    assert flat.count(slow_id) == 1  # still backing off: not retried while others wait
    assert redis_client.hget(SLOW_KEY, str(slow_id)) is not None

    # once the backoff is over the slow metric is retried, after every other metric
    wall.seconds += SLOW_BASE_BACKOFF_SECONDS + 1.0
    ticker = Ticker()
    scorer = _scorer(reader_engine, writer_engine, ticker, cost=0.0, slow={slow_id})
    _run(reader_engine, scorer, redis_client, ticker, wall)

    assert scorer.seen[-1] == slow_id
    assert set(scorer.seen[:-1]) == set(ids[1:])


def test_the_backoff_grows_and_a_recovered_metric_is_forgotten(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    metric = _seed_ref(owner).id
    wall = _Wall()
    delays = []
    for _ in range(3):
        ticker = Ticker()
        scorer = _scorer(reader_engine, writer_engine, ticker, cost=0.0, slow={metric})
        _run(reader_engine, scorer, redis_client, ticker, wall)
        failures, until = str(redis_client.hget(SLOW_KEY, str(metric))).split(":")
        delays.append((int(failures), float(until) - wall.seconds))
        wall.seconds = float(until) + 1.0

    assert delays == [
        (1, SLOW_BASE_BACKOFF_SECONDS),
        (2, 2 * SLOW_BASE_BACKOFF_SECONDS),
        (3, 4 * SLOW_BASE_BACKOFF_SECONDS),
    ]
    ticker = Ticker()
    healthy = _scorer(reader_engine, writer_engine, ticker, cost=0.0)
    _run(reader_engine, healthy, redis_client, ticker, wall)
    assert healthy.seen == [metric]
    assert redis_client.hget(SLOW_KEY, str(metric)) is None


# --- 2. cursor writes are owner-guarded ----------------------------------------------------------


def _takeover(redis: Redis, cursor: str) -> None:
    """The lease of the running run ends, B takes the lock and saves its cursor."""
    redis.delete(LOCK_KEY)
    assert RedisLock(redis, LOCK_KEY, LOCK_TTL_MS).acquire()
    redis.set(CURSOR_KEY, cursor)


class _TakenOver(_Scorer):
    redis: Redis

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        outcome = super().score_metric(metric, budget)
        if len(self.seen) == 1:
            _takeover(self.redis, B_CURSOR)
        return outcome


def _taken_over_scorer(
    reader_engine: Engine, writer_engine: Engine, ticker: Ticker, cost: float, redis: Redis
) -> _TakenOver:
    base = _scorer(reader_engine, writer_engine, ticker, cost)
    scorer = _TakenOver(
        reader=base.reader, anomalies=base.anomalies, states=base.states, clock=Clock()
    )
    scorer.seen, scorer.ticker, scorer.cost, scorer.slow = [], ticker, cost, set()
    scorer.redis = redis
    return scorer


def test_a_run_that_lost_its_lease_does_not_overwrite_the_cursor_of_the_new_holder(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        _seed_ref(owner)
    ticker = Ticker()
    scorer = _taken_over_scorer(reader_engine, writer_engine, ticker, 50.0, redis_client)

    _run(reader_engine, scorer, redis_client, ticker)  # A stops at its deadline: SET path

    assert redis_client.get(CURSOR_KEY) == B_CURSOR  # A's own cursor was not written
    assert redis_client.get(LOCK_KEY) is not None  # B still holds the lock


def test_a_run_that_lost_its_lease_does_not_delete_the_cursor_of_the_new_holder(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    _seed_ref(owner)  # one metric: A finishes the list and would clear the cursor
    ticker = Ticker()
    scorer = _taken_over_scorer(reader_engine, writer_engine, ticker, 0.0, redis_client)

    _run(reader_engine, scorer, redis_client, ticker)  # DEL path

    assert redis_client.get(CURSOR_KEY) == B_CURSOR


def test_the_guarded_write_is_atomic_with_the_owner_check(redis_client: Redis) -> None:
    a = RedisLock(redis_client, LOCK_KEY, 60_000)
    assert a.acquire()
    assert a.guarded("SET", "k", "a")
    redis_client.set(LOCK_KEY, "someone-else")  # A's lease is gone
    assert not a.guarded("SET", "k", "overwritten")
    assert not a.guarded("DEL", "k")
    assert redis_client.get("k") == "a"


# --- 3. notify: a hard total deadline ------------------------------------------------------------


def _dribbling_server() -> tuple[str, threading.Event]:
    """Answers every request with a header block that never ends (one line per 0.2 s)."""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(8)
    server.settimeout(0.2)
    stop = threading.Event()

    def drip(connection: socket.socket) -> None:
        try:
            connection.settimeout(1.0)
            try:
                connection.recv(65536)
            except OSError:
                pass
            connection.sendall(b"HTTP/1.1 202 Accepted\r\n")
            while not stop.is_set():
                connection.sendall(b"X-Pad: 1\r\n")  # always inside the per-operation timeout
                time.sleep(0.2)
        except OSError:
            pass
        finally:
            connection.close()

    def serve() -> None:
        while not stop.is_set():
            try:
                connection, _ = server.accept()
            except OSError:
                continue
            threading.Thread(target=drip, args=(connection,), daemon=True).start()
        server.close()

    threading.Thread(target=serve, daemon=True).start()
    return f"http://127.0.0.1:{server.getsockname()[1]}", stop


def test_a_slowly_trickling_response_cannot_outlast_the_notify_budget() -> None:
    url, stop = _dribbling_server()
    client = ApiCoreClient(url, "n" * 40, timeout=30.0, sleep=lambda _: None)
    try:
        started = time.monotonic()
        result = client.notify(
            [_fake_anomaly(MetricRef(id=uuid.uuid4(), tenant_id=uuid.uuid4()))],
            Budget(started + 0.8),
        )
        elapsed = time.monotonic() - started
    finally:
        stop.set()
        client.close()

    assert (result.delivered, result.failed) == (0, 1)
    assert elapsed < 0.8 + 1.0  # the budget plus a small tolerance, not the 30 s of httpx


# --- 4. database checkout and statements stay inside the budget ------------------------------


def test_an_exhausted_pool_gives_up_with_the_budget(writer_url: str) -> None:
    engine = make_engine(writer_url, pool_size=1, pool_timeout=30.0)  # 1 + 2 overflow
    try:
        held = [connect(engine) for _ in range(3)]
        connections = [context.__enter__() for context in held]
        started = time.monotonic()
        with pytest.raises((BudgetExhausted, PoolBusyError)):
            with connect(engine, Budget(started + 0.3)):
                pass
        elapsed = time.monotonic() - started
        for context in held:
            context.__exit__(None, None, None)
        assert len(connections) == 3
    finally:
        engine.dispose()

    assert elapsed < 1.5  # 0.3 s of budget, not the 30 s pool timeout


def test_the_pool_wait_without_a_budget_is_the_short_pool_timeout(writer_url: str) -> None:
    engine = make_engine(writer_url, pool_size=1, pool_timeout=0.3)
    try:
        held = [connect(engine) for _ in range(3)]
        for context in held:
            context.__enter__()
        started = time.monotonic()
        with pytest.raises(PoolBusyError), connect(engine):
            pass
        elapsed = time.monotonic() - started
        for context in held:
            context.__exit__(None, None, None)
    finally:
        engine.dispose()

    assert 0.2 <= elapsed < 2.0


def test_a_reader_call_with_a_short_budget_on_an_exhausted_pool_is_bounded(
    reader_url: str, owner: Engine
) -> None:
    metric = _seed_ref(owner)
    engine = make_engine(reader_url, pool_size=1, pool_timeout=30.0)
    try:
        held = [connect(engine) for _ in range(3)]
        for context in held:
            context.__enter__()
        started = time.monotonic()
        with pytest.raises((BudgetExhausted, PoolBusyError)):
            MetricReader(engine).load_window(
                metric.id, NOW - timedelta(hours=24), NOW, Budget(started + 0.3)
            )
        elapsed = time.monotonic() - started
        for context in held:
            context.__exit__(None, None, None)
    finally:
        engine.dispose()

    assert elapsed < 1.5


def test_a_new_connection_is_opened_with_a_connect_timeout_inside_the_budget(
    writer_url: str,
) -> None:
    engine = make_engine(writer_url, connect_timeout=30)
    seen: list[object] = []

    @event.listens_for(engine, "do_connect")
    def record(dialect, conn_rec, cargs, cparams):  # type: ignore[no-untyped-def]
        seen.append(cparams["connect_timeout"])

    try:
        with connect(engine, Budget(time.monotonic() + 2.5)):
            pass
    finally:
        engine.dispose()

    assert seen == [3]  # ceil(time left), never the engine-wide 30 s


def test_the_budget_is_checked_right_after_the_checkout(writer_url: str) -> None:
    engine = make_engine(writer_url)
    ticker = Ticker()
    budget = Budget(1.0, ticker)

    @event.listens_for(engine, "connect")
    def slow_connect(dbapi_connection, record):  # type: ignore[no-untyped-def]
        ticker.seconds = 2.0  # opening the connection used up the budget

    statements: list[str] = []

    @event.listens_for(engine, "before_cursor_execute")
    def count(conn, cursor, statement, parameters, context, executemany):  # type: ignore[no-untyped-def]
        statements.append(statement)

    try:
        with pytest.raises(BudgetExhausted), connect(engine, budget):
            pass
    finally:
        engine.dispose()

    assert not any("set_config" in statement for statement in statements)


def _results(count: int) -> list[DetectorResult]:
    return [
        DetectorResult(
            detector=Detector.ZSCORE,
            window_start=NOW + timedelta(seconds=i),
            window_end=NOW + timedelta(seconds=i),
            score=0.7,
            severity=Severity.LOW,
        )
        for i in range(count)
    ]


def test_a_multi_statement_insert_gets_a_fresh_timeout_for_every_statement(
    owner: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner)
    count = INSERT_CHUNK * 2 + 5
    statements: list[str] = []

    @event.listens_for(writer_engine, "before_cursor_execute")
    def record(conn, cursor, statement, parameters, context, executemany):  # type: ignore[no-untyped-def]
        statements.append(statement)

    try:
        inserted = AnomalyWriter(writer_engine).insert_new(
            metric, _results(count), Budget(time.monotonic() + 30.0)
        )
    finally:
        event.remove(writer_engine, "before_cursor_execute", record)

    assert len(inserted) == count
    kinds = ["set" if "set_config" in s else "insert" for s in statements]
    assert kinds == ["set", "insert"] * 3  # a timeout derived from the budget before each chunk


def test_a_budget_that_runs_out_between_insert_statements_rolls_back(
    owner: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner)
    ticker = Ticker()
    budget = Budget(10.0, ticker)

    @event.listens_for(writer_engine, "before_cursor_execute")
    def spend(conn, cursor, statement, parameters, context, executemany):  # type: ignore[no-untyped-def]
        if statement.lstrip().upper().startswith("INSERT"):
            ticker.seconds = 11.0  # the first chunk used up the budget

    try:
        with pytest.raises(BudgetExhausted):
            AnomalyWriter(writer_engine).insert_new(metric, _results(INSERT_CHUNK + 5), budget)
    finally:
        event.remove(writer_engine, "before_cursor_execute", spend)

    with owner.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one() == 0


# --- 7. CAS on the identity, z-score scale -----------------------------------------------------


def test_compare_and_set_does_not_accept_a_recreated_row_with_the_same_version(
    owner: Engine, writer_engine: Engine
) -> None:
    metric = _seed_ref(owner).id
    store = DetectorStateStore(writer_engine)
    original = ForestState(trained_at=NOW, version=1, contamination=0.02, point_count=10)
    recreated = ForestState(
        trained_at=NOW + timedelta(hours=1), version=1, contamination=0.02, point_count=10
    )
    newer = ForestState(
        trained_at=NOW + timedelta(hours=2), version=2, contamination=0.02, point_count=10
    )
    assert store.compare_and_set(metric, Detector.ISOLATION_FOREST, original, expected_version=None)
    with owner.begin() as conn:
        conn.execute(text("DELETE FROM detector_state"))
    assert store.compare_and_set(
        metric, Detector.ISOLATION_FOREST, recreated, expected_version=None
    )

    stale = store.compare_and_set(
        metric,
        Detector.ISOLATION_FOREST,
        newer,
        expected_version=1,
        expected_trained_at=original.trained_at,
    )

    assert not stale  # same version number, different identity
    assert store.get(metric, Detector.ISOLATION_FOREST) == recreated
    assert store.compare_and_set(
        metric,
        Detector.ISOLATION_FOREST,
        newer,
        expected_version=1,
        expected_trained_at=recreated.trained_at,
    )


@pytest.mark.parametrize("scale", [1e-170, 1e-300, 5e-324 * 1e6])
def test_a_spike_in_a_series_of_subnormal_scale_is_still_found(scale: float) -> None:
    values = noisy(300, level=0.0, sigma=1.0)
    values[250] += 30.0
    reference = [(r.window_start, r.severity) for r in zscore.detect(make_frame(values))]

    scaled = [(r.window_start, r.severity) for r in zscore.detect(make_frame(values * scale))]

    assert reference
    assert scaled == reference


def test_the_details_of_a_tiny_series_are_reported_in_its_own_unit() -> None:
    values = noisy(300, level=0.0, sigma=1.0)
    values[250] += 30.0

    unit = zscore.detect(make_frame(values))[0].details
    tiny = zscore.detect(make_frame(values * 1e-170))[0].details

    assert tiny["std"] == pytest.approx(float(unit["std"]) * 1e-170, rel=1e-9)
    assert tiny["value"] == pytest.approx(float(unit["value"]) * 1e-170, rel=1e-9)
    assert np.isclose(float(tiny["z"]), float(unit["z"]))
