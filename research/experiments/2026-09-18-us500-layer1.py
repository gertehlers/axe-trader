"""Layer-1 signal test for the old engine's load-bearing entry rule, on US500.

Hypothesis under test (spec 6.2.3 layer 1, source: `owner-comment` / the archived Java config):

    RSI(7) < 25  AND  close <= lower Bollinger(20, 2.0)  AND  close > EMA(200)      -> LONG

This is pillar 1 of the 5-pillar confluence plus the trend gate — the part `CLAUDE.md` calls
load-bearing ("rsi-oversold: 25 — load-bearing, don't loosen").

SCOPE, stated precisely, because it is easy to overclaim here: this tests ONE rule, not the full
5-vote confluence, and a result about it does NOT transfer to the confluence. Two reasons:

1. `confluence-threshold: 3` over four enabled pillars means any three of four may carry an entry,
   so pillar 1 need not fire at all. The confluence's entry set is therefore not a subset of this
   rule's entry set.
2. Even if it were a subset, a subset of a negative-mean population can have a positive mean.

What this test can establish is whether THIS rule, on its own, predicts anything.

Layer 1 is exit-free and cost-free by design. Indicators are computed on mids; for this rule a
constant bid/ask offset cancels out of all three conditions, so mid vs bid is immaterial.

Development data only: 2024-01-01 -> 2026-07-31 (spec 6.2.2 rule 1). August and September 2026 are
held out and are not touched here.

Run:
    research/engine/.venv/bin/python research/experiments/2026-09-18-us500-layer1.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.diagnose import (forward_returns, percentile_of, run_layer1,
                             shifted_placebo_entries, trades_for_power)
from engine.indicators import bollinger, ema, rsi

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPIC = "US500"
TIMEFRAME = "5min"
DEV_START = pd.Timestamp("2024-01-01T00:00:00Z")
DEV_END = pd.Timestamp("2026-07-31T23:59:00Z")

RSI_PERIOD = 7
RSI_OVERSOLD = 25.0
BB_PERIOD = 20
BB_MULTIPLIER = 2.0
TREND_EMA = 200
WARMUP = max(TREND_EMA, BB_PERIOD, RSI_PERIOD) * 3

HORIZONS = [3, 6, 12, 24, 48]          # 5-minute bars: 15m, 30m, 1h, 2h, 4h
PLACEBO_SETS = 200                      # spec 6.2.2 rule 3 floor
SHIFT_WEEKS = 26                        # +/- half a year of whole-week shifts


def main() -> None:
    minutes = load_cached_minutes(DB, EPIC, CACHE)
    bars = resample(minutes, TIMEFRAME)
    bars = bars[(bars.index >= DEV_START) & (bars.index <= DEV_END)]

    mid_close = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()
    mid_high = ((bars["high_bid"] + bars["high_ask"]) / 2.0).to_numpy()
    mid_low = ((bars["low_bid"] + bars["low_ask"]) / 2.0).to_numpy()
    spread = (bars["close_ask"] - bars["close_bid"]).to_numpy()

    rsi_values = rsi(mid_close, RSI_PERIOD)
    _, _, band_lower = bollinger(mid_close, BB_PERIOD, BB_MULTIPLIER)
    trend = ema(mid_close, TREND_EMA)

    warm = np.zeros(len(bars), dtype=bool)
    warm[WARMUP:] = True
    warm &= ~np.isnan(rsi_values) & ~np.isnan(band_lower) & ~np.isnan(trend)

    fires = warm & (rsi_values < RSI_OVERSOLD) & (mid_close <= band_lower) & (mid_close > trend)
    signal = np.flatnonzero(fires)
    eligible = np.flatnonzero(warm)

    index = bars.index
    buckets = (index.dayofweek.to_numpy() * 24 + index.hour.to_numpy()).astype(int)

    days = (index[-1] - index[0]).days
    print(f"{EPIC} {TIMEFRAME} development window {index[0].date()} -> {index[-1].date()} "
          f"({days} days, {len(bars):,} bars)")
    print(f"mean spread {spread.mean():.3f} pts")
    print(f"signal fires {len(signal):,} times "
          f"({len(signal) / len(eligible) * 100:.2f}% of {len(eligible):,} eligible bars, "
          f"{len(signal) / max(days, 1):.2f}/day)\n")

    times = index.to_numpy().astype("datetime64[m]")
    shifted = shifted_placebo_entries(times, signal, sets=PLACEBO_SETS, seed=20260918,
                                      max_shifts=SHIFT_WEEKS)
    kept = [e for e in shifted if len(e) >= 0.8 * len(signal)]
    print(f"clustering-preserving placebos: {len(kept)}/{PLACEBO_SETS} shifts kept "
          f"(>=80% of entries still in range)\n")

    results = {}
    for horizon in HORIZONS:
        result = run_layer1(mid_close, mid_high, mid_low, buckets, signal, eligible,
                            horizon=horizon, placebo_sets=PLACEBO_SETS)
        shifted_means = np.array([forward_returns(mid_close, e, horizon).mean() for e in kept])
        shifted_percentile = percentile_of(result.signal_mean, shifted_means)
        needed = trades_for_power(effect=float(spread.mean()), sd=result.signal_sd)
        results[horizon] = {
            "minutes": horizon * 5,
            "n": result.signal_n,
            "signal_mean": result.signal_mean,
            "placebo_mean": result.placebo_mean,
            "edge": result.edge,
            "percentile": result.percentile,
            "signal_sd": result.signal_sd,
            "signal_mfe": result.signal_mfe,
            "signal_mae": result.signal_mae,
            "placebo_mfe": result.placebo_mfe,
            "placebo_mae": result.placebo_mae,
            "shifted_placebo_mean": float(shifted_means.mean()),
            "shifted_percentile": shifted_percentile,
            "trades_needed_for_spread_sized_edge": needed,
            "powered": bool(result.signal_n >= needed),
        }

    header = (f"{'horizon':>9} {'n':>7} {'signal':>8} {'placebo':>8} {'edge':>7} "
              f"{'pct':>6} {'shiftPct':>9} {'MFE':>7} {'MAE':>7} {'plaMFE':>7} {'plaMAE':>7} "
              f"{'need n':>8} {'powered':>8}")
    print(header)
    print("-" * len(header))
    for horizon, row in results.items():
        print(f"{row['minutes']:>7}m {row['n']:>7,} {row['signal_mean']:>8.3f} "
              f"{row['placebo_mean']:>8.3f} {row['edge']:>7.3f} {row['percentile']:>6.1f} "
              f"{row['shifted_percentile']:>9.1f} "
              f"{row['signal_mfe']:>7.3f} {row['signal_mae']:>7.3f} "
              f"{row['placebo_mfe']:>7.3f} {row['placebo_mae']:>7.3f} "
              f"{row['trades_needed_for_spread_sized_edge']:>8,} {str(row['powered']):>8}")

    OUT.write_text(json.dumps({
        "epic": EPIC, "timeframe": TIMEFRAME,
        "window": [str(index[0]), str(index[-1])],
        "rule": "RSI(7)<25 AND close<=BB(20,2.0).lower AND close>EMA(200)",
        "bars": len(bars), "eligible": len(eligible), "signal_fires": len(signal),
        "mean_spread": float(spread.mean()),
        "placebo_sets": PLACEBO_SETS,
        "horizons": results,
    }, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
