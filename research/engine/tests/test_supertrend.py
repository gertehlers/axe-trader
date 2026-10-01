import numpy as np
import pytest

from engine.indicators import supertrend

# Every bar is mid ± 1 and the mid moves one point a bar, so the true range is always 2 and ATR is
# exactly 2 from the first bar. With factor 1 the raw bands are mid ± 2, small enough to work by hand.
MIDS = np.array([100.0, 101.0, 102.0, 103.0, 104.0, 103.0, 102.0, 101.0])


def _run(mids, factor=1.0):
    return supertrend(mids + 1.0, mids - 1.0, mids, length=7, factor=factor)


def test_flips_up_ratchets_through_a_pullback_and_flips_down_on_a_close():
    line, direction = _run(MIDS)
    # Starts down, as TradingView does. The upper band holds at 102 until a close beats it (bar 3).
    # The lower band then only rises (101 -> 102) and holds through the 103/102 pullback.
    # Bar 7 closes at 101, below 102, and the line jumps to that bar's upper band.
    assert direction.tolist() == [-1, -1, -1, 1, 1, 1, 1, -1]
    assert line.tolist() == pytest.approx([102, 102, 102, 101, 102, 102, 102, 103])


def test_a_wick_through_the_line_does_not_flip():
    line, direction = _run(MIDS)
    # Bars 5 and 6 have lows of 102 and 101, at or below the 102 line, but they close at or above it.
    assert line[6] == pytest.approx(102.0)
    assert MIDS[6] - 1.0 < line[6]
    assert direction[6] == 1


def test_a_close_exactly_on_the_band_does_not_flip():
    line, direction = _run(MIDS)
    # Bar 2 closes at 102, exactly on the 102 upper band: a flip needs a close strictly beyond it.
    assert MIDS[2] == line[1]
    assert direction[2] == -1


def test_factor_scales_the_distance_from_price():
    line, direction = _run(np.full(20, 100.0), factor=3.0)
    # Flat market: ATR stays 2, so the line sits 3 x 2 = 6 above the mid in the initial downtrend.
    assert direction[-1] == -1
    assert line[-1] == pytest.approx(106.0)
