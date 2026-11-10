"""Shared detector result model, input validation and severity helpers.

Input schema shared by every detector (a ``pandas.DataFrame``):

* ``ts``    - timezone-aware UTC datetimes (``datetime64[<unit>, UTC]``), strictly increasing,
              therefore sorted and without duplicates.
* ``value`` - ``float64``, finite (no NaN and no +/-inf).

Any other column is ignored. An empty frame with the right columns is valid.
"""

from datetime import datetime
from enum import StrEnum

import numpy as np
import pandas as pd
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field


class Detector(StrEnum):
    """Detector identifiers as stored in ``anomaly.detector``."""

    ZSCORE = "ZSCORE"
    ISOLATION_FOREST = "ISOLATION_FOREST"


class Severity(StrEnum):
    """Severity levels as stored in ``anomaly.severity``."""

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class DetectorResult(BaseModel):
    """One anomalous window reported by a detector."""

    model_config = ConfigDict(frozen=True)

    detector: Detector
    window_start: AwareDatetime
    window_end: AwareDatetime
    score: float = Field(ge=0.0, le=1.0)
    severity: Severity
    details: dict[str, float | int | str] = Field(default_factory=dict)


class InvalidSeriesError(ValueError):
    """Raised when a series does not satisfy the documented input schema."""


def validate_series(frame: pd.DataFrame) -> None:
    """Check ``frame`` against the shared input schema or raise ``InvalidSeriesError``."""
    for column in ("ts", "value"):
        if column not in frame.columns:
            raise InvalidSeriesError(f"missing column: {column}")

    ts_dtype = frame["ts"].dtype
    if not isinstance(ts_dtype, pd.DatetimeTZDtype) or str(ts_dtype.tz) != "UTC":
        raise InvalidSeriesError(f"ts must be timezone-aware UTC datetimes, got {ts_dtype}")
    if frame["value"].dtype != np.float64:
        raise InvalidSeriesError(f"value must be float64, got {frame['value'].dtype}")
    if frame["ts"].isna().any():
        raise InvalidSeriesError("ts contains missing values")

    values = frame["value"].to_numpy()
    if not np.isfinite(values).all():
        raise InvalidSeriesError("value contains NaN or infinite numbers")
    if not frame["ts"].is_monotonic_increasing:
        raise InvalidSeriesError("ts must be sorted ascending")
    if frame["ts"].duplicated().any():
        raise InvalidSeriesError("ts contains duplicates")


def to_datetime(value: pd.Timestamp) -> datetime:
    """Convert a pandas timestamp to a plain aware ``datetime``."""
    return value.to_pydatetime()
