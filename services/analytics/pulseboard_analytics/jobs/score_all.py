"""Periodic scoring of every active metric, guarded by a Redis lock."""

import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

import structlog
from apscheduler.schedulers.background import BackgroundScheduler
from redis import Redis

from pulseboard_analytics.clients.api_core import NotifyResult
from pulseboard_analytics.jobs.scoring import WINDOW, MetricScorer
from pulseboard_analytics.locks import RedisLock
from pulseboard_analytics.repositories.models import StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader

LOCK_KEY = "analytics:score_all:lock"
JOB_ID = "score_all_metrics"
NOTIFY_BATCH = 500

_log = structlog.get_logger(__name__)


class Notifier(Protocol):
    """Delivers newly stored anomalies to api-core."""

    def notify(self, anomalies: list[StoredAnomaly]) -> NotifyResult: ...


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
    monotonic: Callable[[], float] = time.monotonic,
) -> RunSummary:
    """Score all metrics with points in the last 24 hours.

    Does nothing when another run holds the lock. A failure while scoring one metric is logged
    and does not stop the others. Newly stored anomalies are delivered in batches of at most
    ``NOTIFY_BATCH`` as they are produced; a delivery failure never affects stored rows.

    The Redis lease is not renewed. Before each metric the run checks that it still owns the
    lease and that ``deadline_seconds`` have not passed; otherwise it stops and logs how many
    metrics were left. Those metrics are scored by the next run, which is safe because every run
    re-scores the whole 24 h window and storing is idempotent. A metric that is already in
    progress when the lease runs out may finish; its writes are idempotent as well.
    """
    lock = RedisLock(redis, LOCK_KEY, lock_ttl_ms, monotonic)
    started = monotonic()
    if not lock.acquire():
        _log.info("score_all_skipped", reason="lock held")
        return RunSummary(skipped=True)
    try:
        now = scorer.clock()
        metrics = reader.active_metrics(now - WINDOW, now)
        scorer.retain({metric.id for metric in metrics})
        pending: list[StoredAnomaly] = []
        failed = inserted = notified = unscored = 0
        for position, metric in enumerate(metrics):
            reason = _stop_reason(lock, started, deadline_seconds, monotonic)
            if reason is not None:
                unscored = len(metrics) - position
                _log.warning("score_all_stopped", reason=reason, unscored_metrics=unscored)
                break
            try:
                stored = scorer.score_metric(metric).inserted
            except Exception:
                failed += 1
                _log.exception("metric_scoring_failed", metric_id=str(metric.id))
                continue
            inserted += len(stored)
            pending.extend(stored)
            while len(pending) >= NOTIFY_BATCH:
                notified += _deliver(notifier, pending[:NOTIFY_BATCH])
                pending = pending[NOTIFY_BATCH:]
        if pending:
            notified += _deliver(notifier, pending)
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


def _stop_reason(
    lock: RedisLock, started: float, deadline_seconds: float, monotonic: Callable[[], float]
) -> str | None:
    if monotonic() - started >= deadline_seconds:
        return "deadline reached"
    if not lock.held():
        return "lock lease lost"
    return None


def _deliver(notifier: Notifier, anomalies: list[StoredAnomaly]) -> int:
    try:
        return notifier.notify(anomalies).delivered
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
