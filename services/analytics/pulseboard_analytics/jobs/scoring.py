"""Scoring of a single metric: load the window, run both detectors, persist."""

import threading
from collections import OrderedDict
from collections.abc import Callable, Collection
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pandas as pd
import structlog

from pulseboard_analytics.detectors import isolation_forest, zscore
from pulseboard_analytics.detectors.base import Detector, DetectorResult
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader, SeriesWindow
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore

WINDOW = timedelta(hours=24)
DEFAULT_MAX_FORESTS = 256
_LOCK_STRIPES = 256

_log = structlog.get_logger(__name__)


def annotate_bucketed(results: list[DetectorResult], window: SeriesWindow) -> list[DetectorResult]:
    """Mark results computed on a bucketed window.

    ``details`` gains ``bucketed: true``, ``bucketSeconds`` and ``rawPoints`` (raw observations
    in the bucket the reported point stands for). The reported timestamp and value are those of
    the real raw point chosen for the bucket. Raw windows are returned unchanged.
    """
    if window.bucket_seconds is None:
        return results
    counts = dict(zip(window.frame["ts"], window.frame["raw_points"], strict=True))
    return [
        result.model_copy(
            update={
                "details": {
                    **result.details,
                    "bucketed": True,
                    "bucketSeconds": window.bucket_seconds,
                    "rawPoints": int(counts.get(pd.Timestamp(result.window_start), 0)),
                }
            }
        )
        for result in results
    ]


@dataclass(frozen=True)
class ScoreOutcome:
    """Detector output for one metric and the rows that were newly stored."""

    results: list[DetectorResult]
    inserted: list[StoredAnomaly]


@dataclass
class MetricScorer:
    """Scores metrics over the window ``[now - 24 h, now]``.

    Fitted forests are kept in memory in an LRU cache of at most ``max_forests`` entries (the
    least recently scored forest is evicted; :meth:`retain` drops metrics that are no longer
    active). The persisted detector state records when and on how many points the forest was
    trained and drives the retrain policy. Reading the state, deciding, training and writing the
    state happen under a lock keyed by the metric, so the scheduler and the manual endpoint
    cannot train the same metric twice at once.
    """

    reader: MetricReader
    anomalies: AnomalyWriter
    states: DetectorStateStore
    clock: Callable[[], datetime] = lambda: datetime.now(UTC)
    max_forests: int = DEFAULT_MAX_FORESTS
    _forests: OrderedDict[UUID, isolation_forest.TrainedForest] = field(default_factory=OrderedDict)
    _guard: threading.Lock = field(default_factory=threading.Lock)
    _metric_locks: list[threading.Lock] = field(
        default_factory=lambda: [threading.Lock() for _ in range(_LOCK_STRIPES)]
    )

    @property
    def cached_forests(self) -> int:
        """Number of fitted forests currently held in memory."""
        with self._guard:
            return len(self._forests)

    def retain(self, active: Collection[UUID]) -> int:
        """Drop the forests of metrics that are not in ``active``; returns how many."""
        keep = set(active)
        with self._guard:
            stale = [metric_id for metric_id in self._forests if metric_id not in keep]
            for metric_id in stale:
                del self._forests[metric_id]
        return len(stale)

    def score_metric(self, metric: MetricRef) -> ScoreOutcome:
        """Run both detectors for ``metric`` and store anomalies not seen before.

        A failure in one detector is logged and does not discard the other detector's results.
        """
        now = self.clock()
        window = self.reader.load_window(metric.id, now - WINDOW, now)
        frame = window.frame

        results: list[DetectorResult] = []
        try:
            results.extend(zscore.detect(frame))
        except Exception:
            _log.exception("detector_failed", detector="ZSCORE", metric_id=str(metric.id))
        try:
            results.extend(self._forest_results(metric, frame, now))
        except Exception:
            _log.exception("detector_failed", detector="ISOLATION_FOREST", metric_id=str(metric.id))

        results = annotate_bucketed(results, window)
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
        with self._metric_locks[metric.id.int % _LOCK_STRIPES]:
            forest = self._train_or_reuse(metric, frame, now)
        return isolation_forest.detect(frame, forest)

    def _train_or_reuse(
        self, metric: MetricRef, frame: pd.DataFrame, now: datetime
    ) -> isolation_forest.TrainedForest:
        state = self.states.get(metric.id, Detector.ISOLATION_FOREST)
        with self._guard:
            cached = self._forests.get(metric.id)
            if cached is not None:
                self._forests.move_to_end(metric.id)

        # This process only has a usable model when it was trained for the persisted version.
        matching = (
            cached
            if cached is not None and state is not None and cached.state.version == state.version
            else None
        )
        if matching is not None and not isolation_forest.needs_retrain(state, len(frame), now):
            return matching

        if (
            state is not None
            and matching is None
            and not isolation_forest.needs_retrain(state, len(frame), now)
        ):
            # Another worker (or an earlier run) persisted this version: fit a local copy of it
            # without publishing anything, so the stored version is never overwritten.
            forest = isolation_forest.train(frame, trained_at=now, version=state.version)
        else:
            version = 1 if state is None else state.version + 1
            forest = isolation_forest.train(frame, trained_at=now, version=version)
            self.states.upsert(metric.id, Detector.ISOLATION_FOREST, forest.state)
        self._remember(metric.id, forest)
        return forest

    def _remember(self, metric_id: UUID, forest: isolation_forest.TrainedForest) -> None:
        with self._guard:
            self._forests[metric_id] = forest
            self._forests.move_to_end(metric_id)
            while len(self._forests) > self.max_forests:
                self._forests.popitem(last=False)
