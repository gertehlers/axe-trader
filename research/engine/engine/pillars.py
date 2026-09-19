"""The 4-pillar confluence vote, ported from the archived Java StrategyFactory.

FROZEN. Do not re-tune and do not "fix" the quirks — the entry is held fixed so that every
difference between exit arms is attributable to the exit (spec 1, 3).

Two replicated quirks, both deliberate (spec 3.2):
  * pillar 3's support/resistance levels come from `lowest`/`highest` INCLUDING the current
    bar, so it votes at every new extreme;
  * pillar 3 uses the extremes of CLOSE, not of high/low.
`fire_rates` exists so an always-on pillar is visible rather than assumed to contribute.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from engine.candles import (bearish_engulfing, bearish_harami, bullish_engulfing,
                            bullish_harami, hammer, shooting_star)
from engine.indicators import atr, bollinger, ema, highest, lowest, rsi, sma

PILLAR_NAMES = ["RSI+BB", "Candle", "S/R", "Vol+Trend"]


@dataclass(frozen=True)
class PillarConfig:
    rsi_period: int = 7
    rsi_oversold: float = 25.0
    rsi_overbought: float = 75.0
    bb_period: int = 20
    bb_multiplier: float = 2.0
    ema_period: int = 50
    trend_ema_period: int = 200
    atr_period: int = 14
    proximity_atr_multiple: float = 0.5
    swing_lookback_bars: int = 10
    volume_sma_period: int = 20
    confluence_threshold: int = 3


@dataclass(frozen=True)
class PillarVotes:
    names: list[str]
    bullish: dict[str, np.ndarray]
    bearish: dict[str, np.ndarray]
    bullish_score: np.ndarray
    bearish_score: np.ndarray
    long_gate: np.ndarray
    short_gate: np.ndarray
    atr: np.ndarray = field(default_factory=lambda: np.array([]))


def compute_pillars(frame: pd.DataFrame, config: PillarConfig) -> PillarVotes:
    mid_open = ((frame["open_bid"] + frame["open_ask"]) / 2.0).to_numpy()
    mid_high = ((frame["high_bid"] + frame["high_ask"]) / 2.0).to_numpy()
    mid_low = ((frame["low_bid"] + frame["low_ask"]) / 2.0).to_numpy()
    mid_close = ((frame["close_bid"] + frame["close_ask"]) / 2.0).to_numpy()
    volume = frame["volume"].to_numpy(dtype=float)

    rsi_values = rsi(mid_close, config.rsi_period)
    _, band_upper, band_lower = bollinger(mid_close, config.bb_period, config.bb_multiplier)
    fast_ema = ema(mid_close, config.ema_period)
    trend_ema = ema(mid_close, config.trend_ema_period)
    atr_values = atr(mid_high, mid_low, mid_close, config.atr_period)
    volume_baseline = sma(volume, config.volume_sma_period)

    proximity = atr_values * config.proximity_atr_multiple
    support = lowest(mid_close, config.swing_lookback_bars)
    resistance = highest(mid_close, config.swing_lookback_bars)
    high_volume = volume > volume_baseline

    bullish = {
        "RSI+BB": (rsi_values < config.rsi_oversold) & (mid_close <= band_lower),
        "Candle": (bullish_engulfing(mid_open, mid_high, mid_low, mid_close)
                   | bullish_harami(mid_open, mid_high, mid_low, mid_close)
                   | hammer(mid_open, mid_high, mid_low, mid_close)),
        "S/R": np.abs(mid_close - support) < proximity,
        "Vol+Trend": high_volume & (mid_close > fast_ema),
    }
    bearish = {
        "RSI+BB": (rsi_values > config.rsi_overbought) & (mid_close >= band_upper),
        "Candle": (bearish_engulfing(mid_open, mid_high, mid_low, mid_close)
                   | bearish_harami(mid_open, mid_high, mid_low, mid_close)
                   | shooting_star(mid_open, mid_high, mid_low, mid_close)),
        "S/R": np.abs(mid_close - resistance) < proximity,
        "Vol+Trend": high_volume & (mid_close < fast_ema),
    }
    for votes in (bullish, bearish):
        for name, value in votes.items():
            votes[name] = np.nan_to_num(np.asarray(value, dtype=float), nan=0.0).astype(bool)

    return PillarVotes(
        names=list(PILLAR_NAMES),
        bullish=bullish,
        bearish=bearish,
        bullish_score=np.vstack([bullish[n] for n in PILLAR_NAMES]).sum(axis=0),
        bearish_score=np.vstack([bearish[n] for n in PILLAR_NAMES]).sum(axis=0),
        long_gate=np.nan_to_num(np.asarray(mid_close > trend_ema, dtype=float), nan=0.0).astype(bool),
        short_gate=np.nan_to_num(np.asarray(mid_close < trend_ema, dtype=float), nan=0.0).astype(bool),
        atr=atr_values,
    )


def fire_rates(votes: PillarVotes, warmup: int) -> dict:
    """How often each pillar votes, and the distribution of the confluence score.

    A pillar firing on nearly every bar contributes nothing to a threshold; this is what makes
    that visible instead of assumed.
    """
    out: dict = {"bullish": {}, "bearish": {}}
    for side, table in (("bullish", votes.bullish), ("bearish", votes.bearish)):
        for name in votes.names:
            out[side][name] = float(table[name][warmup:].mean())
    for side, score in (("bullish", votes.bullish_score), ("bearish", votes.bearish_score)):
        out[f"{side}_score_histogram"] = {int(k): int(v) for k, v in
                                          sorted(Counter(score[warmup:].tolist()).items())}
    return out
