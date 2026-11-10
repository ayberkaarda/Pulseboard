"""Rolling z-score detector.

Each point is compared with the mean and sample standard deviation of the (at most) 288 points
before it, so a spike never dilutes its own baseline. A point is only scored once at least 30
preceding points exist, hence series shorter than 31 points never produce anomalies. A flat
baseline (range of a few ulps of its magnitude) produces no anomalies.

Input: see :mod:`pulseboard_analytics.detectors.base`.
"""

import numpy as np
import pandas as pd

from pulseboard_analytics.detectors.base import (
    Detector,
    DetectorResult,
    Severity,
    flat_range,
    to_datetime,
    validate_series,
)

WINDOW = 288
MIN_POINTS = 30
Z_THRESHOLD = 3.0
Z_MEDIUM = 4.0
Z_HIGH = 5.0
SCORE_FULL_AT = 6.0


def severity_for_z(z: float) -> Severity | None:
    """Map ``|z|`` to a severity; ``None`` below the anomaly threshold."""
    magnitude = abs(z)
    if magnitude >= Z_HIGH:
        return Severity.HIGH
    if magnitude >= Z_MEDIUM:
        return Severity.MEDIUM
    if magnitude >= Z_THRESHOLD:
        return Severity.LOW
    return None


def score_for_z(z: float) -> float:
    """Return ``min(1, |z| / 6)``."""
    return min(1.0, abs(z) / SCORE_FULL_AT)


def detect(frame: pd.DataFrame) -> list[DetectorResult]:
    """Return the anomalous points of ``frame`` ordered by time."""
    validate_series(frame)
    if len(frame) <= MIN_POINTS:
        return []

    values = frame["value"]
    baseline = values.shift(1).rolling(WINDOW, min_periods=MIN_POINTS)
    mean = baseline.mean()
    std = baseline.std()

    # A baseline whose range is only a few ulps of its magnitude is flat, whatever the unit.
    flat = flat_range(baseline.min(), baseline.max())
    z_scores = (values - mean) / std.where(~flat)

    results: list[DetectorResult] = []
    for index in np.flatnonzero(np.isfinite(z_scores.to_numpy())):
        z = float(z_scores.iloc[index])
        severity = severity_for_z(z)
        if severity is None:
            continue
        ts = to_datetime(frame["ts"].iloc[index])
        results.append(
            DetectorResult(
                detector=Detector.ZSCORE,
                window_start=ts,
                window_end=ts,
                score=score_for_z(z),
                severity=severity,
                details={
                    "z": z,
                    "value": float(values.iloc[index]),
                    "mean": float(mean.iloc[index]),
                    "std": float(std.iloc[index]),
                },
            )
        )
    return results
