import numpy as np
import pytest

from engine.indicators import bollinger, ema, rsi, sma


def test_sma_warms_up_then_averages():
    out = sma(np.array([1.0, 2.0, 3.0, 4.0]), 3)
    assert np.isnan(out[0]) and np.isnan(out[1])
    assert out[2] == pytest.approx(2.0)
    assert out[3] == pytest.approx(3.0)


def test_ema_of_a_constant_series_is_that_constant():
    out = ema(np.full(50, 7.0), 10)
    assert out[-1] == pytest.approx(7.0)


def test_ema_reacts_faster_than_sma_early_in_a_step():
    # Three bars into the step, EMA has moved further than SMA. After a full window the SMA
    # catches up completely, so the comparison only means anything while the step is fresh.
    values = np.concatenate([np.full(30, 100.0), np.full(3, 110.0)])
    assert ema(values, 10)[-1] > sma(values, 10)[-1]

    settled = np.concatenate([np.full(30, 100.0), np.full(10, 110.0)])
    assert sma(settled, 10)[-1] == pytest.approx(110.0)


def test_rsi_is_100_when_every_change_is_a_gain():
    out = rsi(np.arange(1.0, 30.0), 7)
    assert out[-1] == pytest.approx(100.0)


def test_rsi_is_zero_when_every_change_is_a_loss():
    out = rsi(np.arange(30.0, 1.0, -1.0), 7)
    assert out[-1] == pytest.approx(0.0)


def test_rsi_of_an_alternating_series_sits_near_fifty():
    values = np.array([100.0 + (1.0 if i % 2 else 0.0) for i in range(60)])
    assert 40.0 < rsi(values, 7)[-1] < 60.0


def test_rsi_warm_up_is_nan_and_never_signals():
    out = rsi(np.arange(1.0, 30.0), 7)
    assert np.isnan(out[:7]).all()
    assert not (out[:7] < 25).any()  # NaN comparisons are False, so warm-up cannot fire a rule


def test_bollinger_bands_straddle_the_middle_and_widen_with_dispersion():
    calm = np.array([100.0] * 25)
    middle, upper, lower = bollinger(calm, 20, 2.0)
    assert upper[-1] == pytest.approx(middle[-1])  # zero dispersion, zero width

    rng = np.random.default_rng(3)
    noisy = 100.0 + rng.normal(0, 2.0, 100)
    middle, upper, lower = bollinger(noisy, 20, 2.0)
    assert upper[-1] > middle[-1] > lower[-1]


def test_bollinger_uses_population_standard_deviation():
    values = np.arange(1.0, 21.0)
    middle, upper, _ = bollinger(values, 20, 1.0)
    assert upper[-1] - middle[-1] == pytest.approx(values.std(ddof=0))


# --- Wilder smoothing, ATR and rolling extremes (task 1) -----------------------------------

from engine.indicators import wilder_mma, true_range, atr, highest, lowest


def test_wilder_mma_seeds_on_the_first_value_like_ta4j():
    # ta4j's MMAIndicator seeds with the first value, then smooths recursively. Seeding on the
    # mean of the first `period` values instead would be a different (and unfaithful) series.
    values = np.array([10.0, 20.0, 30.0])
    out = wilder_mma(values, 2)
    assert out[0] == 10.0
    assert out[1] == (10.0 * 1 + 20.0) / 2       # 15.0
    assert out[2] == (15.0 * 1 + 30.0) / 2       # 22.5


def test_true_range_takes_the_largest_of_the_three_ranges():
    high = np.array([10.0, 12.0])
    low = np.array([9.0, 11.0])
    close = np.array([9.5, 11.5])
    out = true_range(high, low, close)
    assert out[0] == 1.0                  # first bar: high - low
    assert out[1] == 12.0 - 9.5           # gap up: high - previous close


def test_atr_is_wilder_smoothed_true_range():
    high = np.array([10.0, 12.0, 13.0])
    low = np.array([9.0, 11.0, 12.0])
    close = np.array([9.5, 11.5, 12.5])
    assert np.allclose(atr(high, low, close, 2), wilder_mma(true_range(high, low, close), 2))


def test_highest_and_lowest_include_the_current_bar():
    # This is ta4j's behaviour and pillar 3 depends on it: at a new extreme the distance to the
    # level is zero, so "near support" is trivially true.
    values = np.array([5.0, 3.0, 4.0, 2.0])
    assert list(highest(values, 2)) == [5.0, 5.0, 4.0, 4.0]
    assert list(lowest(values, 2)) == [5.0, 3.0, 3.0, 2.0]
    assert lowest(values, 3)[3] == 2.0
