"""Detectors must not depend on the unit or the magnitude of a metric."""

import numpy as np
import pytest

from pulseboard_analytics.detectors import isolation_forest as iforest
from pulseboard_analytics.detectors import zscore
from pulseboard_analytics.detectors.base import Severity
from tests.conftest import NOW
from tests.support.series import make_frame, noisy, seasonal


def _spiky_noise() -> np.ndarray:
    values = noisy(300, level=10.0, sigma=1.0)
    values[250] += 30.0
    values[120] -= 12.0
    return values


def _zscore_signature(values: np.ndarray) -> list[tuple[object, Severity]]:
    return [(r.window_start, r.severity) for r in zscore.detect(make_frame(values))]


def _forest_signature(values: np.ndarray) -> list[tuple[object, Severity]]:
    frame = make_frame(values)
    forest = iforest.train(frame, trained_at=NOW, version=1)
    return [(r.window_start, r.severity) for r in iforest.detect(frame, forest)]


@pytest.mark.parametrize("scale", [1e-12, 1e12])
def test_scaling_a_series_does_not_change_what_is_detected(scale: float) -> None:
    values = _spiky_noise()
    reference_z = _zscore_signature(values)
    reference_forest = _forest_signature(values)

    assert reference_z, "the reference series must contain anomalies"
    assert reference_forest
    assert _zscore_signature(values * scale) == reference_z
    assert _forest_signature(values * scale) == reference_forest


@pytest.mark.parametrize("scale", [1e-12, 1e-30])
def test_tiny_noisy_series_is_not_treated_as_flat(scale: float) -> None:
    frame = make_frame(_spiky_noise() * scale)

    assert iforest.is_trainable(frame)
    assert zscore.detect(frame)


@pytest.mark.parametrize("level", [0.0, 1e-12, 5.0, 1e12])
def test_constant_series_is_flat_at_any_scale(level: float) -> None:
    frame = make_frame([level] * 300)

    assert zscore.detect(frame) == []
    assert not iforest.is_trainable(frame)


@pytest.mark.parametrize("magnitude", [1e40, 1e100])
def test_huge_magnitudes_are_detected_by_both_detectors(magnitude: float) -> None:
    values = seasonal(288)
    values[200] += 80.0
    frame = make_frame(values * magnitude)
    spike_ts = frame["ts"].iloc[200].to_pydatetime()

    forest = iforest.train(frame, trained_at=NOW, version=1)
    forest_hits = {r.window_start: r for r in iforest.detect(frame, forest)}
    z_hits = {r.window_start: r for r in zscore.detect(frame)}

    assert np.isfinite(iforest.anomaly_scores(frame, forest)).all()
    assert np.isfinite(forest.center).all()
    assert np.isfinite(forest.scale).all()
    assert forest_hits[spike_ts].severity is Severity.HIGH
    assert z_hits[spike_ts].severity is Severity.HIGH


def test_huge_series_gives_the_same_forest_result_as_the_unit_series() -> None:
    values = seasonal(288)
    values[200] += 80.0

    assert _forest_signature(values * 1e100) == _forest_signature(values)


# --- flat rule: range based, a few ulps of the magnitude ---------------------------------------


def _offset_series(offset: float, spike: float) -> np.ndarray:
    values = offset + noisy(300, level=0.0, sigma=1.0)
    values[250] += spike
    return values


@pytest.mark.parametrize("offset", [1e9, 1e12])
def test_a_huge_offset_with_a_small_variance_is_not_flat(offset: float) -> None:
    frame = make_frame(_offset_series(offset, spike=100.0))
    spike_ts = frame["ts"].iloc[250].to_pydatetime()

    assert iforest.is_trainable(frame)
    hits = {r.window_start: r for r in zscore.detect(frame)}
    assert hits[spike_ts].severity is Severity.HIGH


def test_the_offset_series_behaves_like_the_series_without_offset() -> None:
    centred = _zscore_signature(_offset_series(0.0, spike=100.0))
    shifted = _zscore_signature(_offset_series(1e12, spike=100.0))

    assert centred
    assert [ts for ts, _ in shifted] == [ts for ts, _ in centred]


@pytest.mark.parametrize("level", [0.0, 3.0, -7.5, 1e12, 1e-300, 5e-324])
def test_a_constant_series_is_flat_including_zero_mean_and_subnormal_levels(level: float) -> None:
    frame = make_frame([level] * 300)

    assert zscore.detect(frame) == []
    assert not iforest.is_trainable(frame)


def test_a_zero_mean_series_with_variance_is_not_flat() -> None:
    values = np.tile([-1.0, 1.0], 150)
    values[250] = 40.0

    assert iforest.is_trainable(make_frame(values))
    assert zscore.detect(make_frame(values))


def test_subnormal_scale_noise_is_not_flat() -> None:
    # Values that differ by whole subnormal steps vary; ulp based rule must not call them flat.
    rng = np.random.default_rng(1)
    values = rng.integers(1, 1000, 300) * 5e-324

    assert not iforest.is_flat(make_frame(values)["value"])


def test_noise_around_a_constant_that_is_within_a_few_ulps_is_flat() -> None:
    base = 1e6
    values = base + np.arange(300) % 3 * np.spacing(base)

    assert iforest.is_flat(make_frame(values)["value"])
    assert zscore.detect(make_frame(values)) == []
