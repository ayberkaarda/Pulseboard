"""Periodic scoring of every active metric, guarded by a Redis lock.

The lock is a lease that is not renewed, and it bounds work; it does not make it exclusive.
Each metric gets a time budget that ends with the lease: waiting for a pooled connection and
opening a new one are capped by what is left (to a whole second for the libpq connect timeout),
every statement carries statement and lock timeouts derived from it (re-derived before each
statement of a multi-statement insert), a check follows the checkout, the training and before
each write, and notification has a hard total deadline enforced by a watchdog. No metric starts
with less than the safety margin left. What can still overlap with the next holder of the lock:
an uninterruptible training run (CPU work, but its result is discarded when the budget is spent,
so it writes nothing), a database statement that is already executing (stopped by its statement
timeout at the lease end, plus the server's own timer granularity) and the clock skew between
this process and Redis. The margin is not a bound on any of these; it only keeps a new metric
from starting without time. Single-writer behaviour is therefore guaranteed by idempotent
stores (decision D1: the anomaly unique key with ``ON CONFLICT DO NOTHING``, and compare-and-set
model state keyed on version and ``trained_at``), not by the lease alone.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

import structlog
from apscheduler.schedulers.background import BackgroundScheduler
from redis import Redis

from pulseboard_analytics import metrics as prom
from pulseboard_analytics.budget import Budget, BudgetExhausted
from pulseboard_analytics.clients.api_core import NotifyResult
from pulseboard_analytics.jobs.scoring import WINDOW, MetricScorer
from pulseboard_analytics.locks import MetricBusyError, RedisLock
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader

LOCK_KEY = "analytics:score_all:lock"
JOB_ID = "score_all_metrics"
NOTIFY_BATCH = 500
CURSOR_KEY = "analytics:score_all:cursor"
CURSOR_TTL_SECONDS = 24 * 3600
SLOW_KEY = "analytics:score_all:slow"
SLOW_TTL_SECONDS = 24 * 3600
SLOW_BASE_BACKOFF_SECONDS = 120.0
SLOW_MAX_BACKOFF_SECONDS = 3600.0
SLOW_MAX_ENTRIES = 500

_log = structlog.get_logger(__name__)


class Notifier(Protocol):
    """Delivers newly stored anomalies to api-core."""

    def notify(self, anomalies: list[StoredAnomaly], budget: Budget | None = None) -> NotifyResult:
        """Deliver ``anomalies``; with a ``budget`` the call never outlasts it."""
        ...


@dataclass(frozen=True)
class RunSummary:
    """Result of one scheduled run."""

    skipped: bool = False
    metrics: int = 0
    failed_metrics: int = 0
    inserted: int = 0
    notified: int = 0
    unscored_metrics: int = 0


def score_all_metrics(
    *,
    reader: MetricReader,
    scorer: MetricScorer,
    notifier: Notifier,
    redis: Redis,
    lock_ttl_ms: int,
    deadline_seconds: float = 50.0,
    lease_margin_seconds: float = 5.0,
    monotonic: Callable[[], float] = time.monotonic,
    wall: Callable[[], float] = time.time,
) -> RunSummary:
    """Score all metrics with points in the last 24 hours.

    Does nothing when another run holds the lock. A failure while scoring one metric is logged
    and does not stop the others; a metric whose every detector failed counts as failed too.
    Newly stored anomalies are delivered in batches of at most ``NOTIFY_BATCH`` as they are
    produced; a delivery failure never affects stored rows.

    Fair scheduling: metrics are visited in id order, starting at the point where the previous
    run stopped (the first unscored metric is kept in Redis, so the rotation survives restarts).
    A run that is cut short therefore never starves the tail: with ``k`` metrics scored per run,
    all ``n`` are scored within ``ceil(n / k)`` runs. A run that finishes clears the cursor.

    Slow metrics: a metric that exhausts its time budget never stays first in line. The cursor
    moves past it, and it is recorded with an exponential backoff (``SLOW_BASE_BACKOFF_SECONDS``
    doubling per failure up to ``SLOW_MAX_BACKOFF_SECONDS``, a Redis hash of at most
    ``SLOW_MAX_ENTRIES`` entries with a TTL). While its backoff runs it is skipped; once due it
    is tried again, but only after every other metric of the run, so it cannot starve them. A
    successful scoring removes the entry. Every Redis write of the cursor and of this table is
    an atomic compare-owner-then-write script, so a run that lost its lease writes nothing.

    Lease: the Redis lease is not renewed. Before each metric the run checks that it still owns
    the lease (the check is repeated against the local clock after the Redis round trip), that
    ``deadline_seconds`` have not passed and that at least ``lease_margin_seconds`` of lease
    remain. The metric gets a budget that ends with the lease: its database statements carry
    statement and lock timeouts derived from it, it never starts an insert or a state write
    after the lease end, and notification gets only the lease time that is left.

    This bounds protected work but does not make it exclusive. Training is CPU work that cannot
    be interrupted, a statement may run until its timeout, and the clock and Redis can disagree;
    so a second run may overlap with the last in-flight metric of the first, by up to the
    margin. Correctness never depends on the lease alone: every store is idempotent (the
    anomaly unique key with ``ON CONFLICT DO NOTHING``, compare-and-set model state), so an
    overlap duplicates work but not results.
    """
    lock = RedisLock(redis, LOCK_KEY, lock_ttl_ms, monotonic)
    started = monotonic()
    if not lock.acquire():
        _log.info("score_all_skipped", reason="lock held")
        prom.score_runs.labels(result="skipped").inc()
        return RunSummary(skipped=True)
    result = "error"
    try:
        lease_end = lock.expires_at()
        now = scorer.clock()
        metrics = reader.active_metrics(now - WINDOW, now, Budget(lease_end, monotonic))
        scorer.retain({metric.id for metric in metrics})
        slow = _read_slow(redis)
        now_wall = wall()
        normal = [m for m in metrics if str(m.id) not in slow]
        due = sorted(
            (m for m in metrics if str(m.id) in slow and slow[str(m.id)][1] <= now_wall),
            key=lambda m: slow[str(m.id)][1],
        )
        waiting = len(metrics) - len(normal) - len(due)
        if waiting:
            _log.info("score_all_slow_metrics_waiting", count=waiting)
        ordered = [*_rotate(normal, _read_cursor(redis)), *due]
        _forget_stale(lock, slow, {m.id for m in metrics})
        pending: list[StoredAnomaly] = []
        failed = inserted = notified = unscored = 0
        resume_from: UUID | None = None
        result = "completed"
        for position, metric in enumerate(ordered):
            reason = _stop_reason(
                lock, started, deadline_seconds, lease_margin_seconds * 1000.0, monotonic
            )
            if reason is not None:
                result = "deadline" if reason == "deadline reached" else "lost_lease"
                unscored = len(ordered) - position
                resume_from = metric.id if position < len(normal) else None
                _log.warning("score_all_stopped", reason=reason, unscored_metrics=unscored)
                break
            try:
                outcome = scorer.score_metric(metric, Budget(lease_end, monotonic))
            except BudgetExhausted:
                failed += 1
                prom.metrics_failed.labels(reason="budget").inc()
                result = "lost_lease"
                unscored = len(ordered) - position
                following = ordered[position + 1] if position + 1 < len(normal) else None
                resume_from = None if following is None else following.id
                _mark_slow(lock, slow, metric.id, now_wall)
                _log.warning("metric_budget_exhausted", metric_id=str(metric.id))
                break
            except MetricBusyError:
                # Retried by the next run; the other metrics go on.
                failed += 1
                prom.metrics_failed.labels(reason="busy").inc()
                _log.warning("metric_busy", metric_id=str(metric.id))
                continue
            except Exception:
                failed += 1
                prom.metrics_failed.labels(reason="error").inc()
                _log.exception("metric_scoring_failed", metric_id=str(metric.id))
                continue
            if str(metric.id) in slow:
                lock.guarded("HDEL", SLOW_KEY, str(metric.id))
            if outcome.failed:
                failed += 1
                prom.metrics_failed.labels(reason="all_detectors_failed").inc()
                _log.error(
                    "metric_analysis_failed",
                    metric_id=str(metric.id),
                    detectors=[detector.value for detector in outcome.errors],
                )
            else:
                prom.metrics_scored.inc()
            inserted += len(outcome.inserted)
            pending.extend(outcome.inserted)
            while len(pending) >= NOTIFY_BATCH:
                notified += _deliver(notifier, pending[:NOTIFY_BATCH], Budget(lease_end, monotonic))
                pending = pending[NOTIFY_BATCH:]
        if pending:
            notified += _deliver(notifier, pending, Budget(lease_end, monotonic))
        _write_cursor(lock, resume_from)
        prom.unscored_metrics.set(unscored)
        prom.slow_metrics.set(len(slow))
        summary = RunSummary(
            metrics=len(metrics),
            failed_metrics=failed,
            inserted=inserted,
            notified=notified,
            unscored_metrics=unscored,
        )
        _log.info("score_all_finished", **summary.__dict__)
        return summary
    finally:
        prom.score_runs.labels(result=result).inc()
        prom.score_run_duration.observe(monotonic() - started)
        lock.release()


def _rotate(metrics: list[MetricRef], cursor: UUID | None) -> list[MetricRef]:
    """Start the id-ordered list at the first metric whose id is at or after ``cursor``."""
    if cursor is None:
        return metrics
    start = next((i for i, metric in enumerate(metrics) if metric.id >= cursor), 0)
    return metrics[start:] + metrics[:start]


def _read_cursor(redis: Redis) -> UUID | None:
    try:
        raw = redis.get(CURSOR_KEY)
        return None if raw is None else UUID(str(raw))
    except Exception:
        _log.warning("score_cursor_unreadable", exc_info=True)
        return None


def _write_cursor(lock: RedisLock, resume_from: UUID | None) -> None:
    """Save or clear the cursor, atomically with the check that this run still owns the lock."""
    try:
        if resume_from is None:
            lock.guarded("DEL", CURSOR_KEY)
        else:
            lock.guarded("SET", CURSOR_KEY, str(resume_from), "EX", CURSOR_TTL_SECONDS)
    except Exception:
        _log.warning("score_cursor_not_saved", exc_info=True)


def _read_slow(redis: Redis) -> dict[str, tuple[int, float]]:
    """The slow-metric table: metric id to ``(failures, retry not before (epoch seconds))``."""
    table: dict[str, tuple[int, float]] = {}
    try:
        raw = redis.hgetall(SLOW_KEY)
        for field, value in raw.items():
            try:
                failures, until = str(value).split(":")
                table[str(field)] = (int(failures), float(until))
            except ValueError:
                continue
    except Exception:
        _log.warning("score_slow_unreadable", exc_info=True)
    return table


def _forget_stale(lock: RedisLock, slow: dict[str, tuple[int, float]], active: set[UUID]) -> None:
    keep = {str(metric_id) for metric_id in active}
    stale = [field for field in slow if field not in keep]
    try:
        for field in stale:
            lock.guarded("HDEL", SLOW_KEY, field)
            del slow[field]
    except Exception:
        _log.warning("score_slow_not_saved", exc_info=True)


def _mark_slow(
    lock: RedisLock, slow: dict[str, tuple[int, float]], metric_id: UUID, now: float
) -> None:
    """Record that ``metric_id`` exhausted its budget; it is retried after a growing backoff."""
    field = str(metric_id)
    failures = slow.get(field, (0, 0.0))[0] + 1
    delay = min(SLOW_MAX_BACKOFF_SECONDS, SLOW_BASE_BACKOFF_SECONDS * 2 ** (failures - 1))
    _log.warning("metric_marked_slow", metric_id=field, failures=failures, retry_in_seconds=delay)
    if field not in slow and len(slow) >= SLOW_MAX_ENTRIES:
        _log.warning("score_slow_table_full", entries=len(slow))
        return
    try:
        lock.guarded("HSET", SLOW_KEY, field, f"{failures}:{now + delay}")
        lock.guarded("EXPIRE", SLOW_KEY, SLOW_TTL_SECONDS)
    except Exception:
        _log.warning("score_slow_not_saved", exc_info=True)


def _stop_reason(
    lock: RedisLock,
    started: float,
    deadline_seconds: float,
    margin_ms: float,
    monotonic: Callable[[], float],
) -> str | None:
    if monotonic() - started >= deadline_seconds:
        return "deadline reached"
    if not lock.held():
        return "lock lease lost"
    if lock.remaining_ms() < margin_ms:
        return "lease margin reached"
    return None


def _deliver(notifier: Notifier, anomalies: list[StoredAnomaly], budget: Budget) -> int:
    try:
        return notifier.notify(anomalies, budget=budget).delivered
    except Exception:
        _log.exception("notify_crashed", count=len(anomalies))
        return 0


def start_scheduler(run: RunCallable, interval_seconds: int) -> BackgroundScheduler:
    """Start a background scheduler that calls ``run`` every ``interval_seconds``."""
    scheduler = BackgroundScheduler(timezone="UTC")
    scheduler.add_job(
        run,
        "interval",
        seconds=interval_seconds,
        id=JOB_ID,
        max_instances=1,
        coalesce=True,
    )
    scheduler.start()
    return scheduler


class RunCallable(Protocol):
    """A no-argument job body."""

    def __call__(self) -> object: ...
