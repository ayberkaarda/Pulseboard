"""Periodic scoring of every active metric, guarded by a Redis lock.

The lock is a lease that is not renewed, and it bounds work; it does not make it exclusive.
Each metric gets a time budget that ends with the lease (database statement and lock timeouts,
a check before the state write and the insert, a total time budget for notification), and no
metric starts with less than the safety margin left. Two things can still overlap with the next
holder of the lock: an uninterruptible training run and a statement that is already executing,
for at most the margin plus the statement timeout. Single-writer behaviour is therefore
guaranteed by idempotent stores (decision D1: the anomaly unique key with ``ON CONFLICT DO
NOTHING``, and compare-and-set model state), not by the lease alone.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

import structlog
from apscheduler.schedulers.background import BackgroundScheduler
from redis import Redis

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
        return RunSummary(skipped=True)
    try:
        lease_end = lock.expires_at()
        now = scorer.clock()
        metrics = reader.active_metrics(now - WINDOW, now, Budget(lease_end, monotonic))
        scorer.retain({metric.id for metric in metrics})
        metrics = _rotate(metrics, _read_cursor(redis))
        pending: list[StoredAnomaly] = []
        failed = inserted = notified = unscored = 0
        resume_from: UUID | None = None
        for position, metric in enumerate(metrics):
            reason = _stop_reason(
                lock, started, deadline_seconds, lease_margin_seconds * 1000.0, monotonic
            )
            if reason is not None:
                unscored = len(metrics) - position
                resume_from = metric.id
                _log.warning("score_all_stopped", reason=reason, unscored_metrics=unscored)
                break
            try:
                outcome = scorer.score_metric(metric, Budget(lease_end, monotonic))
            except BudgetExhausted:
                failed += 1
                unscored = len(metrics) - position
                resume_from = metric.id
                _log.warning("metric_budget_exhausted", metric_id=str(metric.id))
                break
            except MetricBusyError:
                # Retried by the next run; the other metrics go on.
                failed += 1
                _log.warning("metric_busy", metric_id=str(metric.id))
                continue
            except Exception:
                failed += 1
                _log.exception("metric_scoring_failed", metric_id=str(metric.id))
                continue
            if outcome.failed:
                failed += 1
                _log.error(
                    "metric_analysis_failed",
                    metric_id=str(metric.id),
                    detectors=[detector.value for detector in outcome.errors],
                )
            inserted += len(outcome.inserted)
            pending.extend(outcome.inserted)
            while len(pending) >= NOTIFY_BATCH:
                notified += _deliver(notifier, pending[:NOTIFY_BATCH], Budget(lease_end, monotonic))
                pending = pending[NOTIFY_BATCH:]
        if pending:
            notified += _deliver(notifier, pending, Budget(lease_end, monotonic))
        _write_cursor(redis, resume_from)
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


def _write_cursor(redis: Redis, resume_from: UUID | None) -> None:
    try:
        if resume_from is None:
            redis.delete(CURSOR_KEY)
        else:
            redis.set(CURSOR_KEY, str(resume_from), ex=CURSOR_TTL_SECONDS)
    except Exception:
        _log.warning("score_cursor_not_saved", exc_info=True)


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
