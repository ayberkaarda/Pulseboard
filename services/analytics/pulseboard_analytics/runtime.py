"""Wiring of the long-lived collaborators (engines, Redis, clients, scheduler)."""

from dataclasses import dataclass, field

import structlog
from apscheduler.schedulers.background import BackgroundScheduler
from redis import Redis
from sqlalchemy import Engine

from pulseboard_analytics.clients.api_core import ApiCoreClient
from pulseboard_analytics.jobs.score_all import RunSummary, score_all_metrics, start_scheduler
from pulseboard_analytics.jobs.scoring import MetricScorer
from pulseboard_analytics.repositories.engines import make_engine
from pulseboard_analytics.repositories.privileges import verify_database_roles
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from pulseboard_analytics.settings import Settings

_log = structlog.get_logger(__name__)


@dataclass
class Runtime:
    """Everything the HTTP layer and the scheduled job share."""

    settings: Settings
    reader_engine: Engine
    writer_engine: Engine
    redis: Redis
    notifier: ApiCoreClient
    reader: MetricReader
    scorer: MetricScorer
    scheduler: BackgroundScheduler | None = field(default=None)

    @classmethod
    def build(cls, settings: Settings) -> Runtime:
        """Create engines and clients; nothing connects until first use."""
        reader_engine = make_engine(settings.database_reader_url)
        writer_engine = make_engine(settings.database_writer_url)
        reader = MetricReader(reader_engine)
        return cls(
            settings=settings,
            reader_engine=reader_engine,
            writer_engine=writer_engine,
            redis=Redis.from_url(settings.redis_url, decode_responses=True),
            notifier=ApiCoreClient(settings.api_core_base_url, settings.internal_shared_secret),
            reader=reader,
            scorer=MetricScorer(
                reader=reader,
                anomalies=AnomalyWriter(writer_engine),
                states=DetectorStateStore(writer_engine),
                max_forests=settings.forest_cache_max,
                lock_timeout_seconds=float(settings.metric_lock_timeout_seconds),
            ),
        )

    def run_scoring(self) -> RunSummary:
        """One scheduled pass over all active metrics."""
        return score_all_metrics(
            reader=self.reader,
            scorer=self.scorer,
            notifier=self.notifier,
            redis=self.redis,
            lock_ttl_ms=self.settings.lock_ttl_ms,
            deadline_seconds=self.settings.score_deadline_seconds,
            lease_margin_seconds=self.settings.lease_margin_seconds,
        )

    def verify_roles(self) -> None:
        """Refuse to run with database roles that are broader or narrower than intended."""
        if self.settings.verify_db_roles:
            verify_database_roles(self.reader_engine, self.writer_engine)

    def start(self) -> None:
        """Start the scheduler when enabled."""
        if self.settings.scheduler_enabled:
            self.scheduler = start_scheduler(self._safe_run, self.settings.score_interval_seconds)

    def _safe_run(self) -> None:
        try:
            self.run_scoring()
        except Exception:
            _log.exception("score_all_crashed")

    def stop(self) -> None:
        """Stop the scheduler and release connections."""
        if self.scheduler is not None:
            self.scheduler.shutdown(wait=True)
            self.scheduler = None
        self.notifier.close()
        self.redis.close()
        self.reader_engine.dispose()
        self.writer_engine.dispose()
