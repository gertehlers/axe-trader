"""Indicators, vectorised over a closed-bar series.

These are ports of the ta4j indicators the old Java engine used, defined here explicitly rather
than inherited: conventions differ between libraries (Wilder vs simple smoothing, population vs
sample standard deviation, EMA seeding), and a signal test is only meaningful if the definition
is pinned down. Where ta4j had a choice, the convention is named in the docstring.

Every function returns an array the same length as its input, with NaN over the warm-up period.
A NaN never counts as a signal — comparisons against NaN are False — so warm-up bars are
excluded from any rule by construction.
"""

from __future__ import annotations

import numpy as np


def sma(values: np.ndarray, period: int) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    out = np.full(len(values), np.nan)
    if len(values) < period:
        return out
    cumulative = np.cumsum(np.insert(values, 0, 0.0))
    out[period - 1:] = (cumulative[period:] - cumulative[:-period]) / period
    return out


def ema(values: np.ndarray, period: int) -> np.ndarray:
    """EMA seeded on the first value, multiplier 2/(period+1) — ta4j's EMAIndicator convention."""
    values = np.asarray(values, dtype=float)
    out = np.full(len(values), np.nan)
    if len(values) == 0:
        return out
    alpha = 2.0 / (period + 1.0)
    current = values[0]
    out[0] = current
    for i in range(1, len(values)):
        current = values[i] * alpha + current * (1.0 - alpha)
        out[i] = current
    return out


def rsi(closes: np.ndarray, period: int) -> np.ndarray:
    """Wilder's RSI (ta4j RSIIndicator): the first average is a simple mean of `period` changes,
    thereafter smoothed by (prev * (period - 1) + current) / period."""
    closes = np.asarray(closes, dtype=float)
    out = np.full(len(closes), np.nan)
    if len(closes) <= period:
        return out
    changes = np.diff(closes)
    gains = np.where(changes > 0, changes, 0.0)
    losses = np.where(changes < 0, -changes, 0.0)
    average_gain = gains[:period].mean()
    average_loss = losses[:period].mean()
    out[period] = 100.0 if average_loss == 0 else 100.0 - 100.0 / (1.0 + average_gain / average_loss)
    for i in range(period, len(changes)):
        average_gain = (average_gain * (period - 1) + gains[i]) / period
        average_loss = (average_loss * (period - 1) + losses[i]) / period
        out[i + 1] = 100.0 if average_loss == 0 else 100.0 - 100.0 / (1.0 + average_gain / average_loss)
    return out


def bollinger(closes: np.ndarray, period: int, multiplier: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Middle/upper/lower bands. Population standard deviation (ddof=0), as ta4j uses."""
    closes = np.asarray(closes, dtype=float)
    middle = sma(closes, period)
    deviation = np.full(len(closes), np.nan)
    if len(closes) >= period:
        windows = np.lib.stride_tricks.sliding_window_view(closes, period)
        deviation[period - 1:] = windows.std(axis=1, ddof=0)
    return middle, middle + multiplier * deviation, middle - multiplier * deviation


def wilder_mma(values: np.ndarray, period: int) -> np.ndarray:
    """Wilder's modified moving average, seeded on the first value.

    This is ta4j's MMAIndicator, not a simple mean of the first `period` values. The seeding
    difference washes out after a few dozen bars but is replicated so the port matches.
    """
    values = np.asarray(values, dtype=float)
    out = np.full(len(values), np.nan)
    if len(values) == 0:
        return out
    out[0] = values[0]
    for i in range(1, len(values)):
        out[i] = (out[i - 1] * (period - 1) + values[i]) / period
    return out


def true_range(high: np.ndarray, low: np.ndarray, close: np.ndarray) -> np.ndarray:
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    close = np.asarray(close, dtype=float)
    previous = np.empty_like(close)
    previous[0] = close[0]
    previous[1:] = close[:-1]
    return np.maximum(high - low, np.maximum(np.abs(high - previous), np.abs(low - previous)))


def atr(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int) -> np.ndarray:
    return wilder_mma(true_range(high, low, close), period)


def _rolling(values: np.ndarray, period: int, reducer) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    out = np.full(len(values), np.nan)
    for i in range(len(values)):
        out[i] = reducer(values[max(0, i - period + 1): i + 1])
    return out


def highest(values: np.ndarray, period: int) -> np.ndarray:
    """Rolling maximum INCLUDING the current bar, as ta4j's HighestValueIndicator does."""
    return _rolling(values, period, np.max)


def lowest(values: np.ndarray, period: int) -> np.ndarray:
    """Rolling minimum INCLUDING the current bar, as ta4j's LowestValueIndicator does."""
    return _rolling(values, period, np.min)
