import numpy as np

from engine.candles import (bearish_engulfing, bearish_harami, bullish_engulfing,
                            bullish_harami, hammer, shooting_star)


def bars(rows):
    """rows are (open, high, low, close) tuples."""
    a = np.array(rows, dtype=float)
    return a[:, 0], a[:, 1], a[:, 2], a[:, 3]


def test_bullish_engulfing_needs_the_current_body_outside_the_previous_one():
    o, h, l, c = bars([(10.0, 10.5, 8.5, 9.0),    # bearish
                       (8.5, 11.5, 8.4, 11.0)])   # bullish, opens below and closes above
    assert not bullish_engulfing(o, h, l, c)[0]   # first bar can never be a 2-candle pattern
    assert bullish_engulfing(o, h, l, c)[1]


def test_bullish_engulfing_rejects_a_body_that_does_not_clear_the_previous_open():
    o, h, l, c = bars([(10.0, 10.5, 8.5, 9.0),
                       (8.5, 10.0, 8.4, 9.8)])    # closes below the previous open
    assert not bullish_engulfing(o, h, l, c)[1]


def test_bearish_engulfing_mirrors_it():
    o, h, l, c = bars([(9.0, 10.5, 8.5, 10.0),    # bullish
                       (10.5, 10.6, 8.0, 8.5)])   # bearish, opens above and closes below
    assert bearish_engulfing(o, h, l, c)[1]


def test_bullish_harami_needs_the_current_body_inside_the_previous_one():
    o, h, l, c = bars([(11.0, 11.2, 8.8, 9.0),    # bearish, wide
                       (9.5, 10.6, 9.4, 10.5)])   # bullish, contained
    assert bullish_harami(o, h, l, c)[1]


def test_bearish_harami_mirrors_it():
    o, h, l, c = bars([(9.0, 11.2, 8.8, 11.0),    # bullish, wide
                       (10.5, 10.6, 9.4, 9.5)])   # bearish, contained
    assert bearish_harami(o, h, l, c)[1]


def test_hammer_requires_a_long_lower_wick_and_a_downtrend():
    # 40 falling bars establish the ADX downtrend, then the hammer shape.
    base = [(100.0 - i, 101.0 - i, 99.0 - i, 99.5 - i) for i in range(40)]
    base.append((60.0, 60.2, 54.0, 60.1))   # body 0.1, lower wick 6.0, upper wick 0.1
    o, h, l, c = bars(base)
    assert hammer(o, h, l, c)[-1]


def test_hammer_is_false_without_the_trend_even_with_the_shape():
    flat = [(100.0, 100.5, 99.5, 100.2) for _ in range(40)]
    flat.append((100.0, 100.2, 94.0, 100.1))
    o, h, l, c = bars(flat)
    assert not hammer(o, h, l, c)[-1]


def test_shooting_star_requires_a_long_upper_wick_and_an_uptrend():
    base = [(100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i) for i in range(40)]
    base.append((140.0, 146.0, 139.9, 140.1))   # body 0.1, upper wick 5.9, lower wick 0.1
    o, h, l, c = bars(base)
    assert shooting_star(o, h, l, c)[-1]


def test_a_doji_is_never_a_hammer_or_a_shooting_star():
    # Zero body height would divide by zero in the wick ratios.
    base = [(100.0 - i, 101.0 - i, 99.0 - i, 99.5 - i) for i in range(40)]
    base.append((60.0, 60.2, 54.0, 60.0))   # open == close
    o, h, l, c = bars(base)
    assert not hammer(o, h, l, c)[-1]
    assert not shooting_star(o, h, l, c)[-1]
