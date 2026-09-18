import numpy as np
import pandas as pd
import pytest

from engine.diagnose import (excursions, forward_returns, matched_placebo_entries,
                             percentile_of, run_layer1, trades_for_power)


def _series(n=20_000, seed=11):
    """A driftless random walk on a 24-bucket clock, one bucket per bar, cycling."""
    rng = np.random.default_rng(seed)
    steps = rng.normal(0, 1.0, n)
    mid = 100.0 + np.cumsum(steps)
    high = mid + 0.5
    low = mid - 0.5
    buckets = np.arange(n) % 24
    return mid, high, low, buckets


def test_trades_for_power_shrinks_as_the_effect_grows():
    small = trades_for_power(effect=0.1, sd=1.0)
    large = trades_for_power(effect=1.0, sd=1.0)
    assert small > large
    # a 0.1 effect against unit noise needs ~785 observations at 80% power
    assert 700 < small < 900


def test_forward_returns_measures_the_move_over_the_horizon():
    mid = np.array([100.0, 101.0, 103.0, 106.0])
    out = forward_returns(mid, np.array([0, 1]), horizon=2)
    assert out == pytest.approx([3.0, 5.0])


def test_forward_returns_drops_entries_without_a_full_horizon():
    mid = np.array([100.0, 101.0, 102.0])
    assert len(forward_returns(mid, np.array([0, 1, 2]), horizon=2)) == 1


def test_excursions_measure_best_and_worst_within_the_horizon():
    mid = np.array([100.0, 100.0, 100.0, 100.0])
    high = np.array([100.0, 102.0, 101.0, 100.0])
    low = np.array([100.0, 99.0, 97.0, 100.0])
    mfe, mae = excursions(high, low, mid, np.array([0]), horizon=3)
    assert mfe[0] == pytest.approx(2.0)
    assert mae[0] == pytest.approx(3.0)


def test_percentile_of_reports_where_a_value_sits():
    assert percentile_of(5.0, np.arange(10.0)) == pytest.approx(50.0)
    assert percentile_of(100.0, np.arange(10.0)) == pytest.approx(100.0)


def test_placebo_entries_match_the_signal_bucket_composition():
    buckets = np.arange(1000) % 24
    signal = np.array([b for b in range(1000) if buckets[b] == 7][:20])
    eligible = np.arange(1000)

    sets = matched_placebo_entries(buckets, signal, eligible, sets=5, seed=1)

    assert len(sets) == 5
    for entries in sets:
        assert len(entries) == len(signal)
        assert (buckets[entries] == 7).all()  # same hour bucket as the signal


def test_a_planted_edge_is_detected():
    mid, high, low, buckets = _series()
    eligible = np.arange(200, len(mid) - 100)
    signal = eligible[::47][:300]
    # plant a real, signal-specific drift: price rises 3 points over the next 10 bars
    for entry in signal:
        mid[entry + 1: entry + 11] += 3.0
        high[entry + 1: entry + 11] += 3.0
        low[entry + 1: entry + 11] += 3.0

    result = run_layer1(mid, high, low, buckets, signal, eligible, horizon=10, placebo_sets=100)

    assert result.edge > 1.0
    assert result.percentile > 95.0


def test_pure_noise_is_not_detected_as_an_edge():
    mid, high, low, buckets = _series(seed=5)
    eligible = np.arange(200, len(mid) - 100)
    rng = np.random.default_rng(99)
    signal = rng.choice(eligible, size=300, replace=False)

    result = run_layer1(mid, high, low, buckets, signal, eligible, horizon=10, placebo_sets=100)

    assert 5.0 < result.percentile < 95.0


def test_a_clock_only_signal_is_not_credited_as_an_edge():
    """The point of matched times.

    Buckets 8-17 drift up every cycle. A signal that only fires in bucket 7 therefore captures the
    whole drifted stretch over the next 10 bars, and looks brilliant against all-hours entries —
    but it has discovered the clock, not an edge. Matched placebos fire in bucket 7 too, so the
    apparent advantage must vanish.
    """
    n = 20_000
    rng = np.random.default_rng(3)
    steps = rng.normal(0, 1.0, n)
    buckets = np.arange(n) % 24
    steps[(buckets >= 8) & (buckets <= 17)] += 2.0   # a pure time-of-day effect, after bucket 7
    mid = 100.0 + np.cumsum(steps)
    high, low = mid + 0.5, mid - 0.5

    eligible = np.arange(200, n - 100)
    in_bucket = eligible[buckets[eligible] == 7]
    signal = in_bucket[::3][:300]

    matched = run_layer1(mid, high, low, buckets, signal, eligible, horizon=10, placebo_sets=100)

    # Against all-hours entries the same signal would look strong; against matched times it does not.
    all_hours = matched_placebo_entries(np.zeros(n, dtype=int), signal, eligible, sets=100, seed=7)
    naive_means = np.array([forward_returns(mid, e, 10).mean() for e in all_hours])
    naive_percentile = percentile_of(matched.signal_mean, naive_means)

    assert naive_percentile > 95.0        # the clock effect makes it look real
    assert 5.0 < matched.percentile < 95.0  # matched times expose it as the clock


def test_shifted_placebos_preserve_clustering_and_clock_position():
    """The whole point: independent draws lose the signal's bunching, shifts keep it."""
    from engine.diagnose import shifted_placebo_entries

    times = np.arange(np.datetime64("2024-01-01T00:00", "m"),
                      np.datetime64("2024-06-01T00:00", "m"),
                      np.timedelta64(5, "m"))
    # Three tight bursts, placed mid-series so a 4-week shift in either direction stays in range.
    # (288 five-minute bars per day; the series runs 152 days.)
    day = 288
    signal = np.concatenate([np.arange(60 * day, 60 * day + 30),
                             np.arange(75 * day, 75 * day + 30),
                             np.arange(90 * day, 90 * day + 30)])

    sets = shifted_placebo_entries(times, signal, sets=20, seed=5, max_shifts=4)

    assert len(sets) == 20
    for entries in sets:
        if len(entries) == 0:
            continue
        # same number of entries, because a 5-minute grid with no holes maps every shift
        assert len(entries) == len(signal)
        # clustering preserved exactly: the gap structure is identical
        np.testing.assert_array_equal(np.diff(np.sort(entries)), np.diff(np.sort(signal)))
        # whole-week shifts keep weekday and hour
        original = pd.DatetimeIndex(times[signal])
        shifted = pd.DatetimeIndex(times[np.sort(entries)])
        np.testing.assert_array_equal(original.dayofweek.to_numpy(), shifted.dayofweek.to_numpy())
        np.testing.assert_array_equal(original.hour.to_numpy(), shifted.hour.to_numpy())


def test_shifted_placebos_are_not_the_signal_itself():
    from engine.diagnose import shifted_placebo_entries

    times = np.arange(np.datetime64("2024-01-01T00:00", "m"),
                      np.datetime64("2024-06-01T00:00", "m"),
                      np.timedelta64(5, "m"))
    signal = np.arange(2000, 2030)

    for entries in shifted_placebo_entries(times, signal, sets=10, seed=5):
        if len(entries):
            assert not np.array_equal(np.sort(entries), signal)
