"""Synthetic series for detector tests."""

from datetime import UTC, datetime, timedelta

import numpy as np
import pandas as pd

START = datetime(2026, 1, 1, tzinfo=UTC)
STEP = timedelta(minutes=5)


def make_frame(values: list[float] | np.ndarray, start: datetime = START) -> pd.DataFrame:
    """Build a valid input frame with one point every five minutes."""
    ts = pd.date_range(start, periods=len(values), freq="5min", tz="UTC")
    return pd.DataFrame({"ts": ts, "value": np.asarray(values, dtype="float64")})


def noisy(n: int, level: float = 10.0, sigma: float = 1.0, seed: int = 7) -> np.ndarray:
    """Gaussian noise around a constant level."""
    rng = np.random.default_rng(seed)
    return level + rng.normal(0.0, sigma, n)


def seasonal(n: int, seed: int = 7) -> np.ndarray:
    """A daily sine wave (288 points per period) with light noise."""
    rng = np.random.default_rng(seed)
    base = 50.0 + 10.0 * np.sin(2.0 * np.pi * np.arange(n) / 288.0)
    return base + rng.normal(0.0, 0.5, n)
