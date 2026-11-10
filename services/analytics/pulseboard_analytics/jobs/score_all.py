"""Periodic scoring of every active metric, guarded by a Redis lock."""

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


def score_all_metrics(
    *,
    reader: MetricReader,
    scorer: MetricScorer,
    notifier: Notifier,
    redis: Redis,
    lock_ttl_ms: int,
) -> RunSummary:
    """Score all metrics with points in the last 24 hours.

    Does nothing when another run holds the lock. A failure while scoring one metric is logged
    and does not stop the others. Notification happens after the anomalies are stored, and its
    failure never affects them.
    """
    lock = RedisLock(redis, LOCK_KEY, lock_ttl_ms)
    if not lock.acquire():
        _log.info("score_all_skipped", reason="lock held")
        return RunSummary(skipped=True)
    try:
        metrics = reader.active_metrics(scorer.clock() - WINDOW)
        inserted: list[StoredAnomaly] = []
        failed = 0
        for metric in metrics:
            try:
                inserted.extend(scorer.score_metric(metric).inserted)
            except Exception:
                failed += 1
                _log.exception("metric_scoring_failed", metric_id=str(metric.id))
        notified = 0
        if inserted:
            try:
                notified = notifier.notify(inserted).delivered
            except Exception:
                _log.exception("notify_crashed", count=len(inserted))
        summary = RunSummary(
            metrics=len(metrics),
            failed_metrics=failed,
            inserted=len(inserted),
            notified=notified,
        )
        _log.info("score_all_finished", **summary.__dict__)
        return summary
    finally:
        lock.release()


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
