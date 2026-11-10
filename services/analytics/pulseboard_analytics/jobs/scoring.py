"""Scoring of a single metric: load the window, run both detectors, persist."""

import threading
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pandas as pd
import structlog

from pulseboard_analytics.detectors import isolation_forest, zscore
from pulseboard_analytics.detectors.base import Detector, DetectorResult
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore

WINDOW = timedelta(hours=24)

_log = structlog.get_logger(__name__)


@dataclass(frozen=True)
class ScoreOutcome:
    """Detector output for one metric and the rows that were newly stored."""

    results: list[DetectorResult]
    inserted: list[StoredAnomaly]


@dataclass
class MetricScorer:
    """Scores metrics over the last 24 hours.

    Fitted forests are kept in memory per metric; the persisted detector state records when and
    on how many points the forest was trained and drives the retrain policy.
    """

    reader: MetricReader
    anomalies: AnomalyWriter
    states: DetectorStateStore
    clock: Callable[[], datetime] = lambda: datetime.now(UTC)
    _forests: dict[UUID, isolation_forest.TrainedForest] = field(default_factory=dict)
    _guard: threading.Lock = field(default_factory=threading.Lock)

    def score_metric(self, metric: MetricRef) -> ScoreOutcome:
        """Run both detectors for ``metric`` and store anomalies not seen before."""
        now = self.clock()
        frame = self.reader.load_series(metric.id, now - WINDOW)

        results = zscore.detect(frame)
        results.extend(self._forest_results(metric, frame, now))

        inserted = self.anomalies.insert_new(metric, results)
        _log.info(
            "metric_scored",
            metric_id=str(metric.id),
            points=len(frame),
            detected=len(results),
            inserted=len(inserted),
        )
        return ScoreOutcome(results=results, inserted=inserted)

    def _forest_results(
        self, metric: MetricRef, frame: pd.DataFrame, now: datetime
    ) -> list[DetectorResult]:
        if not isolation_forest.is_trainable(frame):
            return []
        with self._guard:
            cached = self._forests.get(metric.id)
        state = self.states.get(metric.id, Detector.ISOLATION_FOREST)
        if isolation_forest.needs_retrain(
            state, len(frame), now, model_available=cached is not None
        ):
            version = 1 if state is None else state.version + 1
            cached = isolation_forest.train(frame, trained_at=now, version=version)
            self.states.upsert(metric.id, Detector.ISOLATION_FOREST, cached.state)
            with self._guard:
                self._forests[metric.id] = cached
        if cached is None:
            return []
        return isolation_forest.detect(frame, cached)
