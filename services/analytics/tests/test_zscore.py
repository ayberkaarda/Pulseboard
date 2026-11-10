"""Rolling z-score detector behaviour on synthetic series."""

import pytest

from pulseboard_analytics.detectors import zscore
from pulseboard_analytics.detectors.base import Detector, Severity
from tests.support.series import make_frame, noisy, seasonal


def test_injected_spike_is_caught() -> None:
    values = noisy(300)
    values[250] += 20.0
    frame = make_frame(values)

    results = zscore.detect(frame)

    assert [r.window_start for r in results] == [frame["ts"].iloc[250].to_pydatetime()]
    spike = results[0]
    assert spike.detector is Detector.ZSCORE
    assert spike.severity is Severity.HIGH
    assert spike.score == 1.0
    assert spike.window_start == spike.window_end
    assert spike.details["z"] > 5.0


def test_negative_spike_is_caught() -> None:
    values = noisy(300)
    values[200] -= 15.0

    results = zscore.detect(make_frame(values))

    assert len(results) == 1
    assert results[0].details["z"] < -3.0


def test_level_shift_is_flagged_at_its_start() -> None:
    values = noisy(300, sigma=0.5)
    values[150:] += 10.0
    frame = make_frame(values)

    results = zscore.detect(frame)

    assert results
    assert results[0].window_start == frame["ts"].iloc[150].to_pydatetime()


def test_seasonal_baseline_is_not_flagged() -> None:
    results = zscore.detect(make_frame(seasonal(864)))

    assert results == []


def test_seasonal_baseline_still_exposes_a_spike() -> None:
    values = seasonal(864)
    values[600] += 30.0
    frame = make_frame(values)

    results = zscore.detect(frame)

    assert [r.window_start for r in results] == [frame["ts"].iloc[600].to_pydatetime()]


@pytest.mark.parametrize("level", [5.0, 0.1, -3.3, 1e6, 0.0])
def test_constant_series_has_no_anomalies(level: float) -> None:
    assert zscore.detect(make_frame([level] * 400)) == []


@pytest.mark.parametrize("size", [0, 1, 10, 29, 30])
def test_too_few_points_have_no_anomalies(size: int) -> None:
    values = list(noisy(size)) if size else []
    if size:
        values[-1] += 500.0

    assert zscore.detect(make_frame(values)) == []


def test_first_scorable_point_is_the_thirty_first() -> None:
    values = list(noisy(31))
    values[30] += 500.0
    frame = make_frame(values)

    results = zscore.detect(frame)

    assert [r.window_start for r in results] == [frame["ts"].iloc[30].to_pydatetime()]


def test_output_is_deterministic_and_finite() -> None:
    values = noisy(400)
    values[300] += 12.0
    frame = make_frame(values)

    first = zscore.detect(frame)
    second = zscore.detect(frame)

    assert first == second
    assert all(0.0 <= r.score <= 1.0 for r in first)
    assert all(v == v and abs(v) != float("inf") for r in first for v in r.details.values())


@pytest.mark.parametrize(
    ("z", "expected"),
    [
        (0.0, None),
        (2.9999999, None),
        (3.0, Severity.LOW),
        (-3.0, Severity.LOW),
        (3.9999999, Severity.LOW),
        (4.0, Severity.MEDIUM),
        (-4.0, Severity.MEDIUM),
        (4.9999999, Severity.MEDIUM),
        (5.0, Severity.HIGH),
        (-5.0, Severity.HIGH),
        (40.0, Severity.HIGH),
    ],
)
def test_severity_boundaries(z: float, expected: Severity | None) -> None:
    assert zscore.severity_for_z(z) is expected


@pytest.mark.parametrize(
    ("z", "expected"), [(0.0, 0.0), (3.0, 0.5), (-3.0, 0.5), (6.0, 1.0), (60.0, 1.0)]
)
def test_score_is_capped_at_one(z: float, expected: float) -> None:
    assert zscore.score_for_z(z) == pytest.approx(expected)
