"""Spec 6.1 step 1 — the cost reality check: where must an edge be biggest to survive?

Every hypothesis tested so far died on costs, not on signal. H-0002 found a real direction worth
+0.565 pts/hour against a 0.561-pt spread; H-0003 showed that ratio ceilings at ~1.55 spreads and
stops improving with holding time. Neither run was aimed anywhere — US500 5-minute bars were
simply what the old Java engine used.

This ranks every instrument x timeframe we hold data for by how much of a typical move the round
trip eats, so the next hypothesis is aimed at the cheapest hunting ground rather than the
historical one. It measures the market, not a strategy, so there is nothing here to overfit and
the holdout is irrelevant — the full history is used.

The hurdle, per round trip, in price points:

    spread          mean(close_ask - close_bid), paid once on entry+exit combined
    financing       P(the bar's window spans the 21:00 UTC charge cut-off)
                    x price x |daily rate| / 100
                    -- zero for intraday bars that miss the cut-off, material at 4h and 1d

    hurdle = spread + financing

and the thing it is measured against:

    median TR       median true range of one bar at that timeframe -- the move actually on offer
    median ATR14    Wilder ATR over 14 bars, the same quantity smoothed

    cost_share = hurdle / median TR

`cost_share` is the headline: the fraction of a typical bar's range consumed by trading it once. A
strategy at that timeframe must capture more than `cost_share` of a typical bar's range before it
earns anything at all. Lower is a better hunting ground.

NATURALGAS is deliberately absent. It is excluded from research permanently (owner, 2026-09-19) --
16.2% missing core minutes that Capital.com cannot supply, and it was never the cheapest ground at
any timeframe anyway. See research/EXCLUDED-INSTRUMENTS.md; do not add it back.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-cost-reality-check.py
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

from engine.bars import resample
from engine.cache import load_cached_minutes

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
SPECS = ROOT / "research" / "engine" / "instruments.yaml"
OUT = Path(__file__).with_suffix(".json")

TIMEFRAMES = {"5m": "5min", "15m": "15min", "1h": "1h", "4h": "4h", "1d": "1D"}
TIMEFRAME_MINUTES = {"5m": 5, "15m": 15, "1h": 60, "4h": 240, "1d": 1440}
CHARGE_HOUR_UTC = 21
ATR_PERIOD = 14

# (epic, label, hour window in UTC or None for all hours)
TARGETS = [
    ("US500", "US500", None),
    ("OIL_BRENT", "OIL_BRENT", None),
    ("OIL_CRUDE", "OIL_CRUDE", None),
]


def wilder_atr(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int) -> np.ndarray:
    previous = np.roll(close, 1)
    previous[0] = close[0]
    true_range = np.maximum(high - low, np.maximum(np.abs(high - previous), np.abs(low - previous)))
    out = np.full(len(true_range), np.nan)
    if len(true_range) <= period:
        return out
    out[period] = true_range[1:period + 1].mean()
    for i in range(period + 1, len(true_range)):
        out[i] = (out[i - 1] * (period - 1) + true_range[i]) / period
    return out


def spans_charge_cutoff(index: pd.DatetimeIndex, minutes: int) -> np.ndarray:
    """Does a bar opening at this timestamp and running `minutes` contain the 21:00 UTC cut-off?

    A daily bar always does. For shorter bars it is the honest fraction: a 5-minute position is
    charged financing only if it happens to straddle 21:00, which is why intraday financing is a
    small expected cost rather than zero or a full night.
    """
    start = index.hour * 60 + index.minute
    cutoff = CHARGE_HOUR_UTC * 60
    if minutes >= 1440:
        return np.ones(len(index), dtype=bool)
    end = start + minutes
    return ((start < cutoff) & (end > cutoff)) | ((end > 1440) & ((end - 1440) > cutoff))


def measure(epic: str, timeframe_label: str, freq: str, specs: dict,
            hours: tuple[int, int] | None) -> dict | None:
    bars = resample(load_cached_minutes(DB, epic, CACHE), freq)
    # NOT `bars["complete"]`. A bar is complete only with every nominal minute present, which
    # excludes every end-of-session bar — and those are exactly the bars that span the 21:00
    # financing cut-off, so filtering on it reports financing as zero. It also deletes every
    # daily bar, since no trading day has 1440 minutes. Instead keep any bar carrying at least
    # half the minutes a typical bar of its kind carries, which adapts to each instrument's real
    # session length and drops only stubs.
    typical_minutes = float(bars["minutes_present"].median())
    bars = bars[bars["minutes_present"] >= 0.5 * typical_minutes]
    if hours is not None:
        low, high = hours
        bars = bars[(bars.index.hour >= low) & (bars.index.hour < high)]
    if len(bars) < ATR_PERIOD * 4:
        return None

    mid_high = ((bars["high_bid"] + bars["high_ask"]) / 2.0).to_numpy()
    mid_low = ((bars["low_bid"] + bars["low_ask"]) / 2.0).to_numpy()
    mid_close = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()

    spread = float((bars["close_ask"] - bars["close_bid"]).mean())
    median_price = float(np.median(mid_close))

    previous = np.roll(mid_close, 1)
    previous[0] = mid_close[0]
    true_range = np.maximum(mid_high - mid_low,
                            np.maximum(np.abs(mid_high - previous), np.abs(mid_low - previous)))
    median_tr = float(np.median(true_range[1:]))
    atr = wilder_atr(mid_high, mid_low, mid_close, ATR_PERIOD)
    median_atr = float(np.nanmedian(atr))

    fee = specs[epic]["overnight_fee"]
    worst_rate = max(abs(fee["long_rate"]), abs(fee["short_rate"]))
    minutes = TIMEFRAME_MINUTES[timeframe_label]
    charged_share = float(spans_charge_cutoff(bars.index, minutes).mean())
    financing = charged_share * median_price * worst_rate / 100.0

    hurdle = spread + financing
    return {
        "bars": int(len(bars)),
        "typical_minutes_per_bar": typical_minutes,
        "median_price": median_price,
        "spread_pts": spread,
        "financing_pts": financing,
        "financed_share_of_bars": charged_share,
        "hurdle_pts": hurdle,
        "median_tr_pts": median_tr,
        "median_atr14_pts": median_atr,
        "cost_share_of_tr": hurdle / median_tr if median_tr else None,
        "cost_share_of_atr": hurdle / median_atr if median_atr else None,
        "spread_bps_of_price": spread / median_price * 10_000,
    }


def main() -> None:
    specs = yaml.safe_load(SPECS.read_text())["instruments"]
    results = {}

    header = (f"{'instrument':>38} {'tf':>4} {'bars':>8} {'spread':>8} {'finan':>7} "
              f"{'hurdle':>7} {'medTR':>8} {'medATR':>8} {'cost/TR':>8} {'cost/ATR':>9}")
    print(header)
    print("-" * len(header))

    for epic, label, hours in TARGETS:
        results[label] = {"epic": epic, "hours_utc": list(hours) if hours else None, "timeframes": {}}
        for tf_label, freq in TIMEFRAMES.items():
            stats = measure(epic, tf_label, freq, specs, hours)
            if stats is None:
                continue
            results[label]["timeframes"][tf_label] = stats
            print(f"{label:>38} {tf_label:>4} {stats['bars']:>8,} {stats['spread_pts']:>8.4f} "
                  f"{stats['financing_pts']:>7.4f} {stats['hurdle_pts']:>7.4f} "
                  f"{stats['median_tr_pts']:>8.4f} {stats['median_atr14_pts']:>8.4f} "
                  f"{stats['cost_share_of_tr']:>7.1%} {stats['cost_share_of_atr']:>8.1%}")
        print()

    # The ranking. NATURALGAS all-hours is excluded: it fails data quality and only the
    # restricted window is a real candidate.
    ranked = []
    for label, entry in results.items():
        if "all hours" in label:
            continue
        for tf_label, stats in entry["timeframes"].items():
            ranked.append((stats["cost_share_of_tr"], label, tf_label, stats))
    ranked.sort()

    print("=== ranked: cheapest hunting ground first (cost as a share of a typical bar's range) ===")
    print(f"{'#':>3} {'instrument':>38} {'tf':>4} {'cost/TR':>8} {'hurdle pts':>11} {'medTR pts':>10}")
    print("-" * 80)
    for i, (share, label, tf_label, stats) in enumerate(ranked, start=1):
        print(f"{i:>3} {label:>38} {tf_label:>4} {share:>7.1%} "
              f"{stats['hurdle_pts']:>11.4f} {stats['median_tr_pts']:>10.4f}")

    OUT.write_text(json.dumps({
        "generated": "2026-09-19",
        "note": "market measurement, not a strategy — full history used, no holdout applies",
        "atr_period": ATR_PERIOD,
        "charge_hour_utc": CHARGE_HOUR_UTC,
        "instruments": results,
        "ranking": [{"rank": i, "instrument": label, "timeframe": tf, "cost_share_of_tr": share}
                    for i, (share, label, tf, _) in enumerate(ranked, start=1)],
    }, indent=2, default=str))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
