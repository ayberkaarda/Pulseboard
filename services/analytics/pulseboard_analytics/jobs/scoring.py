"""Scoring of a single metric: load the window, run both detectors, persist."""

import threading
from collections import OrderedDict
from collections.abc import Callable, Collection
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pandas as pd
import structlog

from pulseboard_analytics.budget import Budget, BudgetExhausted
from pulseboard_analytics.detectors import isolation_forest, zscore
from pulseboard_analytics.detectors.base import Detector, DetectorResult
from pulseboard_analytics.locks import KeyedLocks, MetricBusyError
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.reader import MetricReader, SeriesWindow
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore

WINDOW = timedelta(hours=24)
DEFAULT_MAX_FORESTS = 256
DEFAULT_LOCK_TIMEOUT_SECONDS = 10.0

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
    """Detector output for one metric and the rows that were newly stored.

    ``attempted`` lists the detectors that ran and ``errors`` those among them that failed.
    """

    results: list[DetectorResult]
    inserted: list[StoredAnomaly]
    attempted: tuple[Detector, ...] = ()
    errors: tuple[Detector, ...] = ()

    @property
    def failed(self) -> bool:
        """True when detectors ran and every one of them failed: no analysis happened."""
        return bool(self.attempted) and len(self.errors) == len(self.attempted)


@dataclass
class MetricScorer:
    """Scores metrics over the window ``[now - 24 h, now]``.

    Fitted forests are kept in memory in an LRU cache of at most ``max_forests`` entries (the
    least recently scored forest is evicted; :meth:`retain` drops metrics that are no longer
    active).

    Model identity: the persisted ``detector_state`` row is the snapshot of record. Its params
    carry a content identity (``trained_at``, observation count and a hash of the training
    frame). A freshly trained forest is only cached and used under its own version when the
    persisted write was a real compare-and-set that this process won. A process that lost the
    race, or that finds a current snapshot it does not hold, refits for local use: the model
    carries the winner's version but is marked local (``model_local`` instead of
    ``model_version`` in the details) and never writes. The retrain policy compares the raw
    observation count, also for bucketed windows.

    Locking: a lock keyed by the exact metric id (``KeyedLocks``, bounded by the number of
    concurrent callers) is taken only to train or refit, never for reuse, and waiting for it is
    bounded by ``lock_timeout_seconds``; on timeout the metric fails with ``MetricBusyError``
    and is retried by the next run.
    """

    reader: MetricReader
    anomalies: AnomalyWriter
    states: DetectorStateStore
    clock: Callable[[], datetime] = lambda: datetime.now(UTC)
    max_forests: int = DEFAULT_MAX_FORESTS
    lock_timeout_seconds: float = DEFAULT_LOCK_TIMEOUT_SECONDS
    _forests: OrderedDict[UUID, isolation_forest.TrainedForest] = field(default_factory=OrderedDict)
    _guard: threading.Lock = field(default_factory=threading.Lock)
    _locks: KeyedLocks = field(default_factory=KeyedLocks)

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

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        """Run both detectors for ``metric`` and store anomalies not seen before.

        A failure in one detector is logged and does not discard the other detector's results;
        the outcome lists the failed detectors. ``budget`` bounds all database work: each
        statement gets a timeout derived from what is left, and nothing new starts once it is
        spent (``BudgetExhausted``).
        """
        now = self.clock()
        window = self.reader.load_window(metric.id, now - WINDOW, now, budget)
        frame = window.frame

        results: list[DetectorResult] = []
        attempted = [Detector.ZSCORE]
        errors: list[Detector] = []
        try:
            results.extend(zscore.detect(frame))
        except Exception:
            errors.append(Detector.ZSCORE)
            _log.exception("detector_failed", detector="ZSCORE", metric_id=str(metric.id))
        try:
            if isolation_forest.is_trainable(frame):
                attempted.append(Detector.ISOLATION_FOREST)
                forest = self._forest(metric, window, now, budget)
                results.extend(isolation_forest.detect(frame, forest))
        except MetricBusyError, BudgetExhausted:
            raise
        except Exception:
            errors.append(Detector.ISOLATION_FOREST)
            _log.exception("detector_failed", detector="ISOLATION_FOREST", metric_id=str(metric.id))

        results = annotate_bucketed(results, window)
        inserted = self.anomalies.insert_new(metric, results, budget)
        _log.info(
            "metric_scored",
            metric_id=str(metric.id),
            points=window.raw_count,
            detected=len(results),
            inserted=len(inserted),
            failed_detectors=[d.value for d in errors],
        )
        return ScoreOutcome(
            results=results,
            inserted=inserted,
            attempted=tuple(attempted),
            errors=tuple(errors),
        )

    def _forest(
        self, metric: MetricRef, window: SeriesWindow, now: datetime, budget: Budget | None
    ) -> isolation_forest.TrainedForest:
        state = self.states.get(metric.id, Detector.ISOLATION_FOREST, budget)
        reusable = self._reusable(metric.id, state, window.raw_count, now)
        if reusable is not None:
            return reusable
        timeout = self.lock_timeout_seconds
        if budget is not None:
            timeout = min(timeout, max(0.0, budget.remaining_seconds()))
        with self._locks.hold(metric.id, timeout):
            # Somebody may have published while this caller waited for the lock.
            state = self.states.get(metric.id, Detector.ISOLATION_FOREST, budget)
            reusable = self._reusable(metric.id, state, window.raw_count, now)
            if reusable is not None:
                return reusable
            return self._fit(metric, window, state, now, budget)

    def _reusable(
        self,
        metric_id: UUID,
        state: isolation_forest.ForestState | None,
        raw_count: int,
        now: datetime,
    ) -> isolation_forest.TrainedForest | None:
        """The cached forest when it corresponds to the persisted snapshot and is still fresh."""
        if state is None or isolation_forest.needs_retrain(state, raw_count, now):
            return None
        with self._guard:
            cached = self._forests.get(metric_id)
            if cached is None or cached.persisted_identity != state.identity:
                return None
            self._forests.move_to_end(metric_id)
            return cached

    def _fit(
        self,
        metric: MetricRef,
        window: SeriesWindow,
        state: isolation_forest.ForestState | None,
        now: datetime,
        budget: Budget | None,
    ) -> isolation_forest.TrainedForest:
        frame = window.frame
        if state is not None and not isolation_forest.needs_retrain(state, window.raw_count, now):
            # A current snapshot exists but this process does not hold it: refit for local use.
            forest = isolation_forest.train(frame, trained_at=now, version=state.version)
            return self._remember(metric.id, self._as_local(forest, state.identity))

        expected = None if state is None else state.version
        version = 1 if state is None else state.version + 1
        forest = isolation_forest.train(frame, trained_at=now, version=version)
        if budget is not None:
            budget.timeout_ms()  # raises BudgetExhausted: training may have used up the budget
        won = self.states.compare_and_set(
            metric.id,
            Detector.ISOLATION_FOREST,
            forest.state,
            expected_version=expected,
            expected_trained_at=None if state is None else state.trained_at,
            budget=budget,
        )
        if won:
            return self._remember(metric.id, forest)
        winner = self.states.get(metric.id, Detector.ISOLATION_FOREST, budget)
        if winner is None:
            # The row is gone (the metric was deleted): use the model for this call only.
            return self._as_local(forest, "")
        return self._remember(metric.id, self._as_local(forest, winner.identity, winner.version))

    @staticmethod
    def _as_local(
        forest: isolation_forest.TrainedForest, adopted: str, version: int | None = None
    ) -> isolation_forest.TrainedForest:
        state = forest.state if version is None else replace(forest.state, version=version)
        return replace(forest, state=state, local=True, adopted=adopted)

    def _remember(
        self, metric_id: UUID, forest: isolation_forest.TrainedForest
    ) -> isolation_forest.TrainedForest:
        with self._guard:
            self._forests[metric_id] = forest
            self._forests.move_to_end(metric_id)
            while len(self._forests) > self.max_forests:
                self._forests.popitem(last=False)
        return forest
