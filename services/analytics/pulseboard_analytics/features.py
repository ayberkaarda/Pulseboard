"""Feature engineering for the isolation forest.

Input: see :mod:`pulseboard_analytics.detectors.base`. The output has one row per input row, the
same index, and exactly the columns in ``FEATURE_COLUMNS`` (all finite).
"""

import numpy as np
import pandas as pd

from pulseboard_analytics.detectors.base import validate_series

ROLLING_WINDOW = 12
FEATURE_COLUMNS = (
    "value",
    "diff_1",
    "rolling_mean_12",
    "rolling_std_12",
    "hour_of_day_sin",
    "hour_of_day_cos",
)


def build_features(frame: pd.DataFrame) -> pd.DataFrame:
    """Return the isolation forest feature matrix for ``frame``."""
    validate_series(frame)
    values = frame["value"]
    ts = frame["ts"]
    hour = ts.dt.hour + ts.dt.minute / 60.0 + ts.dt.second / 3600.0
    angle = 2.0 * np.pi * hour / 24.0
    rolling = values.rolling(ROLLING_WINDOW, min_periods=1)
    return pd.DataFrame(
        {
            "value": values,
            "diff_1": values.diff().fillna(0.0),
            "rolling_mean_12": rolling.mean(),
            "rolling_std_12": rolling.std().fillna(0.0),
            "hour_of_day_sin": np.sin(angle),
            "hour_of_day_cos": np.cos(angle),
        },
        index=frame.index,
    )[list(FEATURE_COLUMNS)]
