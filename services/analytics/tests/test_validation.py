"""Input schema validation shared by every detector and the feature builder."""

from collections.abc import Callable
from typing import Any

import numpy as np
import pandas as pd
import pytest

from pulseboard_analytics.detectors import isolation_forest, zscore
from pulseboard_analytics.detectors.base import InvalidSeriesError, validate_series
from pulseboard_analytics.features import build_features
from tests.support.series import make_frame, noisy


def _with_value(index: int, value: float) -> pd.DataFrame:
    frame = make_frame(noisy(100))
    frame.loc[index, "value"] = value
    return frame


def _unsorted() -> pd.DataFrame:
    frame = make_frame(noisy(100))
    return frame.iloc[::-1].reset_index(drop=True)


def _duplicate_ts() -> pd.DataFrame:
    frame = make_frame(noisy(100))
    frame.loc[50, "ts"] = frame.loc[49, "ts"]
    return frame


def _naive_ts() -> pd.DataFrame:
    frame = make_frame(noisy(100))
    frame["ts"] = frame["ts"].dt.tz_localize(None)
    return frame


def _non_utc_ts() -> pd.DataFrame:
    frame = make_frame(noisy(100))
    frame["ts"] = frame["ts"].dt.tz_convert("Europe/Istanbul")
    return frame


def _integer_values() -> pd.DataFrame:
    frame = make_frame(noisy(100))
    frame["value"] = frame["value"].round().astype("int64")
    return frame


BAD_FRAMES: dict[str, Callable[[], pd.DataFrame]] = {
    "nan": lambda: _with_value(10, float("nan")),
    "positive-inf": lambda: _with_value(10, float("inf")),
    "negative-inf": lambda: _with_value(10, float("-inf")),
    "unsorted": _unsorted,
    "duplicate-ts": _duplicate_ts,
    "naive-ts": _naive_ts,
    "non-utc-ts": _non_utc_ts,
    "integer-values": _integer_values,
    "missing-value-column": lambda: make_frame(noisy(100)).drop(columns=["value"]),
    "missing-ts-column": lambda: make_frame(noisy(100)).drop(columns=["ts"]),
}


@pytest.mark.parametrize("name", sorted(BAD_FRAMES))
def test_validate_series_rejects(name: str) -> None:
    with pytest.raises(InvalidSeriesError):
        validate_series(BAD_FRAMES[name]())


@pytest.mark.parametrize("name", sorted(BAD_FRAMES))
@pytest.mark.parametrize(
    "entry_point",
    [zscore.detect, build_features],
    ids=["zscore", "features"],
)
def test_entry_points_reject_invalid_series(
    name: str, entry_point: Callable[[pd.DataFrame], Any]
) -> None:
    with pytest.raises(InvalidSeriesError):
        entry_point(BAD_FRAMES[name]())


@pytest.mark.parametrize("name", sorted(BAD_FRAMES))
def test_forest_training_rejects_invalid_series(name: str) -> None:
    frame = BAD_FRAMES[name]()
    with pytest.raises(InvalidSeriesError):
        isolation_forest.train(frame, trained_at=pd.Timestamp.now(tz="UTC"), version=1)


def test_empty_frame_with_the_right_schema_is_valid() -> None:
    empty = pd.DataFrame(
        {
            "ts": pd.Series([], dtype="datetime64[us, UTC]"),
            "value": pd.Series([], dtype="float64"),
        }
    )

    validate_series(empty)

    assert zscore.detect(empty) == []
    assert len(build_features(empty)) == 0


def test_valid_frame_passes() -> None:
    validate_series(make_frame(np.arange(5, dtype="float64")))
