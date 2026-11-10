"""Isolation forest detector.

Features come from :func:`pulseboard_analytics.features.build_features`. ``train`` fits a forest
on a window and remembers the range of its decision function; ``detect`` scores a window with
a trained forest. ``score = 1 - normalised decision_function`` where the decision function is
min-max normalised with the range seen at training time (clipped to ``[0, 1]``), so higher means
more anomalous. A point is an anomaly at ``score >= 0.65``; severity splits ``[0.65, 1.0]`` into
tertiles (LOW, MEDIUM, HIGH).

Input: see :mod:`pulseboard_analytics.detectors.base`. Series shorter than ``MIN_POINTS`` or with
a flat value column cannot be trained on and produce no anomalies.

Features are standardised before fitting and scoring (median and MAD, falling back to the
standard deviation; the parameters are stored with the forest). scikit-learn converts its input
to float32, which would overflow for the accepted magnitudes up to 1e100 and make the result
depend on the unit of the metric. Standardised features are clipped to +/-``_CLIP``.

The functions are deterministic: the forest uses ``random_state=42``.
"""

import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest

from pulseboard_analytics.detectors.base import (
    Detector,
    DetectorResult,
    Severity,
    flat_range,
    to_datetime,
    validate_series,
)
from pulseboard_analytics.features import build_features

N_ESTIMATORS = 200
CONTAMINATION = 0.02
RANDOM_STATE = 42
MIN_POINTS = 60
SCORE_THRESHOLD = 0.65
MEDIUM_FROM = SCORE_THRESHOLD + (1.0 - SCORE_THRESHOLD) / 3.0
HIGH_FROM = SCORE_THRESHOLD + 2.0 * (1.0 - SCORE_THRESHOLD) / 3.0
RETRAIN_INTERVAL = timedelta(hours=6)
RETRAIN_GROWTH = 1.2
_CLIP = 1e6
_MAD_TO_STD = 1.4826


class StandardizationError(ValueError):
    """Raised when the features cannot be brought to a finite, comparable scale."""


@dataclass(frozen=True)
class ForestState:
    """Persisted training metadata (``detector_state.params``)."""

    trained_at: datetime
    version: int
    contamination: float
    point_count: int
    data_hash: str = ""

    @property
    def identity(self) -> str:
        """Content identity of the snapshot: ``trained_at``, observation count and data hash.

        Two processes hold the same model snapshot exactly when their identities are equal; the
        version number alone says nothing about the training data.
        """
        stamp = self.trained_at.astimezone(UTC).isoformat()
        return f"{stamp}|{self.point_count}|{self.data_hash}"


@dataclass(frozen=True)
class TrainedForest:
    """A fitted forest with the decision function range used for normalisation.

    ``local`` marks a forest that was refitted for use in this process only because another
    process persisted the snapshot of record; it carries that snapshot's version (``state``) but
    never claims it, and ``adopted`` is the identity of the persisted snapshot it stands in for.
    """

    model: IsolationForest
    center: np.ndarray
    scale: np.ndarray
    decision_min: float
    decision_max: float
    state: ForestState
    local: bool = False
    adopted: str = ""

    @property
    def persisted_identity(self) -> str:
        """Identity of the persisted snapshot this forest corresponds to."""
        return self.adopted if self.local else self.state.identity


def data_hash(frame: pd.DataFrame) -> str:
    """SHA-256 over the timestamps and values of ``frame`` (the training data identity)."""
    digest = hashlib.sha256()
    stamps = frame["ts"].dt.tz_convert("UTC").dt.tz_localize(None).astype("datetime64[ns]")
    digest.update(stamps.to_numpy().astype("int64").tobytes())
    digest.update(frame["value"].to_numpy(dtype="float64").tobytes())
    return digest.hexdigest()


def severity_for_score(score: float) -> Severity | None:
    """Map an isolation forest score to a severity; ``None`` below the threshold."""
    if score >= HIGH_FROM:
        return Severity.HIGH
    if score >= MEDIUM_FROM:
        return Severity.MEDIUM
    if score >= SCORE_THRESHOLD:
        return Severity.LOW
    return None


def needs_retrain(
    state: ForestState | None, point_count: int, now: datetime, *, model_available: bool = True
) -> bool:
    """Decide whether the forest must be refitted.

    True without a persisted state, without an in-memory model, when the model is at least
    6 hours old, or when the raw observation count grew by at least 20 % since training
    (``point_count`` is the raw count, not the number of buckets of a downsampled window).
    """
    if state is None or not model_available:
        return True
    if now - state.trained_at >= RETRAIN_INTERVAL:
        return True
    return point_count >= state.point_count * RETRAIN_GROWTH


def observation_count(frame: pd.DataFrame) -> int:
    """Raw observations behind ``frame``: its ``raw_points`` sum when bucketed, else its length."""
    if "raw_points" in frame.columns:
        return int(frame["raw_points"].sum())
    return len(frame)


def is_trainable(frame: pd.DataFrame) -> bool:
    """Whether ``frame`` has enough varying data to fit a forest."""
    if len(frame) < MIN_POINTS:
        return False
    return not is_flat(frame["value"])


def is_flat(values: pd.Series) -> bool:
    """Whether the range of ``values`` is only a few ulps of their magnitude (see ``base``)."""
    low = pd.Series([float(values.min())])
    high = pd.Series([float(values.max())])
    return bool(flat_range(low, high).iloc[0])


def _standardization(features: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    center = np.median(features, axis=0)
    scale = _MAD_TO_STD * np.median(np.abs(features - center), axis=0)
    fallback = features.std(axis=0)
    scale = np.where(scale > 0.0, scale, fallback)
    # A column that is still constant keeps scale 1 and becomes all zeros after centering.
    scale = np.where(scale > 0.0, scale, 1.0)
    if not (np.isfinite(center).all() and np.isfinite(scale).all()):
        raise StandardizationError("feature scale is not finite")
    return center, scale


def _transform(features: pd.DataFrame, center: np.ndarray, scale: np.ndarray) -> np.ndarray:
    with np.errstate(over="ignore", invalid="ignore"):
        scaled = (features.to_numpy() - center) / scale
    if np.isnan(scaled).any():
        raise StandardizationError("standardised features contain NaN")
    return np.asarray(np.clip(scaled, -_CLIP, _CLIP), dtype=np.float64)


def train(frame: pd.DataFrame, *, trained_at: datetime, version: int) -> TrainedForest:
    """Fit a forest on ``frame``; raises ``ValueError`` when it is not trainable."""
    validate_series(frame)
    if not is_trainable(frame):
        raise ValueError("series is too short or flat to train an isolation forest")
    model = IsolationForest(
        n_estimators=N_ESTIMATORS, contamination=CONTAMINATION, random_state=RANDOM_STATE
    )
    features = build_features(frame)
    center, scale = _standardization(features.to_numpy())
    matrix = _transform(features, center, scale)
    model.fit(matrix)
    decision = model.decision_function(matrix)
    return TrainedForest(
        model=model,
        center=center,
        scale=scale,
        decision_min=float(decision.min()),
        decision_max=float(decision.max()),
        state=ForestState(
            trained_at=trained_at,
            version=version,
            contamination=CONTAMINATION,
            point_count=observation_count(frame),
            data_hash=data_hash(frame),
        ),
    )


def anomaly_scores(frame: pd.DataFrame, forest: TrainedForest) -> np.ndarray:
    """Return the anomaly score in ``[0, 1]`` of every row of ``frame``."""
    matrix = _transform(build_features(frame), forest.center, forest.scale)
    decision = forest.model.decision_function(matrix)
    span = forest.decision_max - forest.decision_min
    if span <= 0.0:
        return np.zeros(len(frame))
    normalised = np.clip((decision - forest.decision_min) / span, 0.0, 1.0)
    return np.asarray(1.0 - normalised, dtype=np.float64)


def _details(value: float, forest: TrainedForest) -> dict[str, float | int | str | bool]:
    if forest.local:
        # A local stand-in must not be reported as the persisted version.
        return {"value": value, "model_local": True}
    return {"value": value, "model_version": forest.state.version}


def detect(frame: pd.DataFrame, forest: TrainedForest) -> list[DetectorResult]:
    """Return the anomalous points of ``frame`` according to ``forest``."""
    validate_series(frame)
    if not is_trainable(frame):
        return []
    scores = anomaly_scores(frame, forest)
    results: list[DetectorResult] = []
    for index in np.flatnonzero(scores >= SCORE_THRESHOLD):
        score = float(scores[index])
        severity = severity_for_score(score)
        if severity is None:
            continue
        ts = to_datetime(frame["ts"].iloc[index])
        results.append(
            DetectorResult(
                detector=Detector.ISOLATION_FOREST,
                window_start=ts,
                window_end=ts,
                score=score,
                severity=severity,
                details=_details(float(frame["value"].iloc[index]), forest),
            )
        )
    return results
