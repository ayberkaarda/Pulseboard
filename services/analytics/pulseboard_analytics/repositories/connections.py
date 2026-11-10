"""Connection checkout that cannot outlast a time budget.

SQLAlchemy's ``pool_timeout`` is fixed per engine, so a call with 100 ms left would still wait
for a free connection for the whole pool timeout. Every checkout therefore goes through a
per-engine gate with as many permits as the pool has connections (``pool_size + max_overflow``):
a caller waits for a permit for at most ``min(time left, pool timeout)``, so the pool itself
never has to block. A new physical connection is opened with a libpq ``connect_timeout`` taken
from the time left (rounded up to a whole second, the libpq resolution, and capped by the
connect timeout of the engine). The budget is checked again right after the checkout, so no
statement is sent when it is spent. Callers without a budget wait at most the pool timeout.
"""

import threading
import weakref
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from math import ceil

from sqlalchemy import Connection, Engine, event

from pulseboard_analytics.budget import Budget, BudgetExhausted

DEFAULT_POOL_TIMEOUT_SECONDS = 5.0
DEFAULT_CONNECT_TIMEOUT_SECONDS = 5

_connect_budget: ContextVar[Budget | None] = ContextVar("connect_budget", default=None)


class PoolBusyError(RuntimeError):
    """No database connection became free within the allowed wait."""


class _Gate:
    def __init__(self, permits: int, pool_timeout: float) -> None:
        self.semaphore = threading.BoundedSemaphore(permits)
        self.pool_timeout = pool_timeout


_gates: weakref.WeakKeyDictionary[Engine, _Gate] = weakref.WeakKeyDictionary()
_guard = threading.Lock()


def configure(
    engine: Engine,
    *,
    pool_timeout: float = DEFAULT_POOL_TIMEOUT_SECONDS,
    connect_timeout: int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
) -> None:
    """Install the gate and the connect timeout hook for ``engine`` (called by ``make_engine``)."""
    pool = engine.pool
    permits = int(pool.size()) + int(getattr(pool, "_max_overflow", 0))  # type: ignore[attr-defined]
    with _guard:
        _gates[engine] = _Gate(max(1, permits), pool_timeout)

    @event.listens_for(engine, "do_connect")
    def _connect_timeout(dialect: object, conn_rec: object, cargs: list, cparams: dict) -> None:  # type: ignore[type-arg]
        budget = _connect_budget.get()
        seconds = connect_timeout
        if budget is not None:
            seconds = min(seconds, max(1, ceil(budget.remaining_seconds())))
        cparams["connect_timeout"] = seconds


def _gate(engine: Engine) -> _Gate | None:
    with _guard:
        return _gates.get(engine)


@contextmanager
def connect(engine: Engine, budget: Budget | None = None) -> Iterator[Connection]:
    """Check out a connection within the budget; raises before any statement if it is spent."""
    gate = _gate(engine)
    if gate is None:
        configure(engine)
        gate = _gate(engine)
    assert gate is not None
    wait = gate.pool_timeout
    if budget is not None:
        wait = min(wait, budget.remaining_seconds())
        if wait <= 0.0:
            raise BudgetExhausted("time budget exhausted before the connection checkout")
    if not gate.semaphore.acquire(timeout=wait):
        if budget is not None and budget.remaining_seconds() <= 0.0:
            raise BudgetExhausted("time budget exhausted waiting for a connection")
        raise PoolBusyError(f"no database connection free within {wait:.1f}s")
    token = _connect_budget.set(budget)
    try:
        conn = engine.connect()
    except BaseException:
        gate.semaphore.release()
        raise
    finally:
        _connect_budget.reset(token)
    try:
        if budget is not None:
            budget.timeout_ms()  # raises BudgetExhausted: the checkout used up the budget
        yield conn
    finally:
        try:
            conn.close()
        finally:
            gate.semaphore.release()


@contextmanager
def begin(engine: Engine, budget: Budget | None = None) -> Iterator[Connection]:
    """Like :func:`connect`, inside a transaction that commits on success."""
    with connect(engine, budget) as conn, conn.begin():
        yield conn
