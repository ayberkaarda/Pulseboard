"""Isolation forest detector, feature builder and retrain policy."""

import math
from datetime import UTC, datetime, timedelta

import numpy as np
import pandas as pd
import pytest

from pulseboard_analytics.detectors import isolation_forest as iforest
from pulseboard_analytics.detectors.base import Detector, Severity
from pulseboard_analytics.features import FEATURE_COLUMNS, build_features
from tests.support.series import make_frame, noisy, seasonal

NOW = datetime(2026, 3, 1, 12, 0, tzinfo=UTC)


def _train(values: np.ndarray) -> tuple[iforest.TrainedForest, pd.DataFrame]:
    frame = make_frame(values)
    return iforest.train(frame, trained_at=NOW, version=1), frame


def test_injected_spike_is_caught() -> None:
    values = seasonal(288)
    values[200] += 80.0
    forest, frame = _train(values)

    results = iforest.detect(frame, forest)

    spike_ts = frame["ts"].iloc[200].to_pydatetime()
    flagged = {r.window_start: r for r in results}
    assert spike_ts in flagged
    assert flagged[spike_ts].severity is Severity.HIGH
    assert flagged[spike_ts].detector is Detector.ISOLATION_FOREST
    assert flagged[spike_ts].score >= iforest.SCORE_THRESHOLD


def test_clean_seasonal_series_flags_only_the_tail() -> None:
    forest, frame = _train(seasonal(288))

    results = iforest.detect(frame, forest)

    assert len(results) <= 0.12 * 288
    assert all(0.0 <= r.score <= 1.0 for r in results)


def test_scores_stay_within_unit_interval_for_unseen_extremes() -> None:
    forest, _ = _train(seasonal(288))
    far = make_frame(np.array([1e9] * 70 + [-1e9] * 5, dtype="float64"))

    scores = iforest.anomaly_scores(far, forest)

    assert scores.min() >= 0.0
    assert scores.max() <= 1.0
    assert scores.max() == pytest.approx(1.0)


@pytest.mark.parametrize("level", [5.0, 0.1, 0.0])
def test_constant_series_has_no_anomalies(level: float) -> None:
    frame = make_frame([level] * 300)

    assert not iforest.is_trainable(frame)
    with pytest.raises(ValueError, match="flat"):
        iforest.train(frame, trained_at=NOW, version=1)
    donor, _ = _train(seasonal(288))
    assert iforest.detect(frame, donor) == []


def test_short_series_is_not_trainable() -> None:
    frame = make_frame(noisy(iforest.MIN_POINTS - 1))

    assert not iforest.is_trainable(frame)
    assert iforest.is_trainable(make_frame(noisy(iforest.MIN_POINTS)))


def test_results_are_deterministic_with_fixed_seed() -> None:
    values = seasonal(288)
    values[100] += 60.0
    frame = make_frame(values)

    first = iforest.detect(frame, iforest.train(frame, trained_at=NOW, version=1))
    second = iforest.detect(frame, iforest.train(frame, trained_at=NOW, version=1))

    assert first
    assert first == second


def test_training_records_the_documented_parameters() -> None:
    forest, _ = _train(seasonal(288))

    assert forest.state.contamination == 0.02
    assert forest.state.point_count == 288
    assert forest.state.version == 1
    assert forest.state.trained_at == NOW
    assert forest.model.n_estimators == 200
    assert forest.model.random_state == 42
    assert forest.model.contamination == 0.02


def test_feature_matrix_has_the_documented_columns() -> None:
    frame = make_frame(seasonal(50))

    features = build_features(frame)

    assert tuple(features.columns) == FEATURE_COLUMNS
    assert len(features) == 50
    assert np.isfinite(features.to_numpy()).all()
    assert features["diff_1"].iloc[0] == 0.0
    assert features["rolling_std_12"].iloc[0] == 0.0
    assert features["value"].iloc[7] == frame["value"].iloc[7]
    assert features["rolling_mean_12"].iloc[20] == pytest.approx(frame["value"].iloc[9:21].mean())
    # 2026-01-01 00:00 UTC is hour 0: sin 0, cos 1; one point later is five minutes further.
    assert features["hour_of_day_sin"].iloc[0] == pytest.approx(0.0)
    assert features["hour_of_day_cos"].iloc[0] == pytest.approx(1.0)
    five_minutes = 2.0 * math.pi * (5.0 / 60.0) / 24.0
    assert features["hour_of_day_sin"].iloc[1] == pytest.approx(math.sin(five_minutes))


@pytest.mark.parametrize(
    ("score", "expected"),
    [
        (0.0, None),
        (math.nextafter(iforest.SCORE_THRESHOLD, 0.0), None),
        (iforest.SCORE_THRESHOLD, Severity.LOW),
        (math.nextafter(iforest.MEDIUM_FROM, 0.0), Severity.LOW),
        (iforest.MEDIUM_FROM, Severity.MEDIUM),
        (math.nextafter(iforest.HIGH_FROM, 0.0), Severity.MEDIUM),
        (iforest.HIGH_FROM, Severity.HIGH),
        (1.0, Severity.HIGH),
    ],
)
def test_severity_tertiles(score: float, expected: Severity | None) -> None:
    assert iforest.severity_for_score(score) is expected


def test_tertile_edges_split_the_interval_in_three() -> None:
    assert iforest.MEDIUM_FROM == pytest.approx(0.65 + 0.35 / 3)
    assert iforest.HIGH_FROM == pytest.approx(0.65 + 0.7 / 3)


def _state(age: timedelta = timedelta(0), points: int = 100) -> iforest.ForestState:
    return iforest.ForestState(
        trained_at=NOW - age, version=3, contamination=0.02, point_count=points
    )


def test_retrain_when_there_is_no_state() -> None:
    assert iforest.needs_retrain(None, 100, NOW)


def test_retrain_when_the_model_is_not_in_memory() -> None:
    assert iforest.needs_retrain(_state(), 100, NOW, model_available=False)


def test_no_retrain_for_a_fresh_stable_model() -> None:
    assert not iforest.needs_retrain(_state(timedelta(hours=1)), 100, NOW)
    assert not iforest.needs_retrain(_state(timedelta(hours=5, minutes=59)), 100, NOW)


def test_retrain_exactly_at_six_hours() -> None:
    assert iforest.needs_retrain(_state(timedelta(hours=6)), 100, NOW)


def test_retrain_when_the_point_count_grew_twenty_percent() -> None:
    assert not iforest.needs_retrain(_state(points=100), 119, NOW)
    assert iforest.needs_retrain(_state(points=100), 120, NOW)
    assert iforest.needs_retrain(_state(points=100), 500, NOW)


def test_shrinking_window_alone_does_not_retrain() -> None:
    assert not iforest.needs_retrain(_state(points=100), 40, NOW)
