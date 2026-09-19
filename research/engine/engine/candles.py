"""The six ta4j 0.22.6 candle patterns pillar 2 votes on, ported exactly.

Ported from the ta4j sources rather than from the config, because two of them are not what
their names suggest: `hammer` and `shooting_star` each require an ADX-based trend filter on top
of the wick-ratio test. A shape-only port would be a different strategy (spec 3.2).

All six take (open, high, low, close) as mid prices and return boolean arrays.
"""

from __future__ import annotations

import numpy as np

from engine.indicators import down_trend, up_trend

HAMMER_BODY_TO_BOTTOM_WICK = 2.0
HAMMER_BODY_TO_UPPER_WICK = 1.0
STAR_BODY_TO_BOTTOM_WICK = 1.0
STAR_BODY_TO_UPPER_WICK = 2.0


def _previous(values: np.ndarray) -> np.ndarray:
    out = np.empty_like(values)
    out[0] = values[0]
    out[1:] = values[:-1]
    return out


def _two_bar(open_, close, bullish_first: bool, inside: bool) -> np.ndarray:
    """Engulfing and harami share a shape: previous body one way, current the other.

    `inside` picks harami (current body within the previous one) over engulfing (outside it).
    """
    open_ = np.asarray(open_, dtype=float)
    close = np.asarray(close, dtype=float)
    prev_open, prev_close = _previous(open_), _previous(close)

    if bullish_first:
        direction_ok = (prev_close > prev_open) & (close < open_)     # prev bullish, curr bearish
        if inside:      # bearish harami
            body_ok = ((open_ > prev_open) & (open_ < prev_close)
                       & (close > prev_open) & (close < prev_close))
        else:           # bearish engulfing
            body_ok = ((open_ > prev_open) & (open_ > prev_close)
                       & (close < prev_open) & (close < prev_close))
    else:
        direction_ok = (prev_close < prev_open) & (close > open_)     # prev bearish, curr bullish
        if inside:      # bullish harami
            body_ok = ((open_ < prev_open) & (open_ > prev_close)
                       & (close < prev_open) & (close > prev_close))
        else:           # bullish engulfing
            body_ok = ((open_ < prev_open) & (open_ < prev_close)
                       & (close > prev_open) & (close > prev_close))

    out = direction_ok & body_ok
    out[0] = False      # a two-candle pattern cannot exist on the first bar
    return out


def bullish_engulfing(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=False, inside=False)


def bearish_engulfing(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=True, inside=False)


def bullish_harami(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=False, inside=True)


def bearish_harami(open_, high, low, close) -> np.ndarray:
    return _two_bar(open_, close, bullish_first=True, inside=True)


def _wick_ratios(open_, high, low, close):
    open_ = np.asarray(open_, dtype=float)
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    close = np.asarray(close, dtype=float)
    body = np.abs(close - open_)
    upper_boundary = np.maximum(open_, close)
    lower_boundary = np.minimum(open_, close)
    with np.errstate(divide="ignore", invalid="ignore"):
        bottom = (lower_boundary - low) / body
        upper = (high - upper_boundary) / body
    # A doji has zero body: the ratios are non-finite and the pattern is defined as false.
    finite = body > 0
    return bottom, upper, finite


def hammer(open_, high, low, close) -> np.ndarray:
    bottom, upper, finite = _wick_ratios(open_, high, low, close)
    shape = finite & (bottom > HAMMER_BODY_TO_BOTTOM_WICK) & (upper <= HAMMER_BODY_TO_UPPER_WICK)
    return shape & down_trend(high, low, close)


def shooting_star(open_, high, low, close) -> np.ndarray:
    bottom, upper, finite = _wick_ratios(open_, high, low, close)
    shape = finite & (upper > STAR_BODY_TO_UPPER_WICK) & (bottom <= STAR_BODY_TO_BOTTOM_WICK)
    return shape & up_trend(high, low, close)
