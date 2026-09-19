"""Layer 0 census: find the good runs first, then look backwards at what preceded them.

The owner's idea, and it inverts the usual order. Every hypothesis so far started from a signal and
asked whether it predicted anything. This starts from the OUTCOME — clean directional moves — and
asks whether anything at their start distinguishes them from an ordinary bar.

This is EXPLORATORY and selects on the future by construction. Nothing here can promote a
hypothesis; anything that looks predictive must be pre-registered and tested on the untouched
slices. The three-way split exists for exactly that:

    discovery  2024-01-01 .. 2025-09-30   patterns may be searched for here
    validation 2025-10-01 .. 2026-07-31   a found pattern is checked here, once
    holdout    2026-08-01 ..              not touched by this script at all

Run definition is calibrated from research/marks/2026-09-19-owner-oil-runs.json — the 12 runs the
owner drew by hand — not chosen by me: >= 4 ATR displacement within 4..28 bars.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-run-census.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.indicators import atr as atr_ind, ema, highest, lowest, rsi, sma
from engine.pillars import bollinger

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPICS = ["OIL_CRUDE", "US500", "OIL_BRENT"]
TIMEFRAME = "15min"
SLICES = {
    "discovery":  (pd.Timestamp("2024-01-01T00:00:00Z"), pd.Timestamp("2025-09-30T23:59:00Z")),
    "validation": (pd.Timestamp("2025-10-01T00:00:00Z"), pd.Timestamp("2026-07-31T23:59:00Z")),
}
MIN_ATR = 4.0        # the owner's marks run 2.6 .. 11.6, median 5.1
MIN_BARS, MAX_BARS = 4, 28   # his 60 .. 420 minutes
WARMUP = 600


def find_runs(close, high, low, atr, min_atr=MIN_ATR):
    """Greedy, non-overlapping. At each bar, the furthest clean displacement within the window.

    Non-overlapping matters: a trending stretch would otherwise report a run starting at every bar
    and the census would count the same move dozens of times.
    """
    n = len(close)
    runs = []
    i = WARMUP
    while i < n - MAX_BARS - 1:
        a = atr[i]
        if not a or np.isnan(a) or a <= 0:
            i += 1
            continue
        best = None
        for k in range(MIN_BARS, MAX_BARS + 1):
            disp = (close[i + k] - close[i]) / a
            if abs(disp) < min_atr:
                continue
            direction = 1 if disp > 0 else -1
            seg_hi = high[i + 1:i + k + 1].max()
            seg_lo = low[i + 1:i + k + 1].min()
            mae = (close[i] - seg_lo) / a if direction > 0 else (seg_hi - close[i]) / a
            mfe = (seg_hi - close[i]) / a if direction > 0 else (close[i] - seg_lo) / a
            if best is None or abs(disp) > abs(best["disp"]):
                best = {"i": i, "k": k, "disp": float(disp), "dir": int(direction),
                        "mae": float(mae), "mfe": float(mfe)}
        if best:
            runs.append(best)
            i += best["k"]          # skip past it
        else:
            i += 1
    return runs


def main() -> None:
    results = {"min_atr": MIN_ATR, "window_bars": [MIN_BARS, MAX_BARS], "epics": {}}

    for epic in EPICS:
        full = resample(load_cached_minutes(DB, epic, CACHE), TIMEFRAME)
        results["epics"][epic] = {}
        print(f"\n{'='*70}\n{epic} {TIMEFRAME}\n{'='*70}")

        for slice_name, (lo_t, hi_t) in SLICES.items():
            bars = full[(full.index >= lo_t) & (full.index <= hi_t)]
            if len(bars) < WARMUP + MAX_BARS + 10:
                print(f"  {slice_name}: too few bars"); continue
            mid_c = ((bars["close_bid"] + bars["close_ask"]) / 2).to_numpy()
            mid_h = ((bars["high_bid"] + bars["high_ask"]) / 2).to_numpy()
            mid_l = ((bars["low_bid"] + bars["low_ask"]) / 2).to_numpy()
            vol = bars["volume"].to_numpy(dtype=float)
            a = atr_ind(mid_h, mid_l, mid_c, 14)

            runs = find_runs(mid_c, mid_h, mid_l, a)
            days = (bars.index[-1] - bars.index[0]).days or 1
            disp = np.array([abs(r["disp"]) for r in runs])
            mae = np.array([r["mae"] for r in runs])
            kk = np.array([r["k"] for r in runs])
            clean = mae <= 1.0

            rec = {
                "bars": int(len(bars)), "days": int(days), "runs": int(len(runs)),
                "runs_per_day": round(len(runs) / days, 3),
                "long": int(sum(r["dir"] > 0 for r in runs)),
                "short": int(sum(r["dir"] < 0 for r in runs)),
                "disp_atr_median": float(np.median(disp)) if len(disp) else None,
                "duration_bars_median": float(np.median(kk)) if len(kk) else None,
                "mae_atr_median": float(np.median(mae)) if len(mae) else None,
                "clean_share": float(clean.mean()) if len(mae) else None,
                "clean_per_day": round(float(clean.sum()) / days, 3) if len(mae) else None,
            }
            results["epics"][epic][slice_name] = rec
            print(f"  {slice_name:10s} {len(bars):6,} bars / {days:4d} days -> "
                  f"{len(runs):4d} runs ({rec['runs_per_day']:.2f}/day, "
                  f"L{rec['long']}/S{rec['short']})")
            if len(disp):
                print(f"             size {np.median(disp):.1f} ATR · {np.median(kk):.0f} bars · "
                      f"MAE {np.median(mae):.2f} ATR · clean(MAE<=1) {clean.mean():.0%} "
                      f"= {rec['clean_per_day']:.2f}/day")

            # --- what was true at the run's first bar, vs every eligible bar -----------
            if slice_name == "discovery" and len(runs) >= 30 and epic == "OIL_CRUDE":
                r7 = rsi(mid_c, 7)
                _, bb_u, bb_l = bollinger(mid_c, 20, 2.0)
                e50, e200 = ema(mid_c, 50), ema(mid_c, 200)
                vsma = sma(vol, 20)
                swing_hi, swing_lo = highest(mid_c, 10), lowest(mid_c, 10)
                width = np.divide(bb_u - bb_l, a, out=np.zeros_like(a), where=a > 0)
                atr_pct = pd.Series(a).rolling(500, min_periods=100).rank(pct=True).to_numpy()
                idxs = np.array([r["i"] for r in runs])
                base = np.arange(WARMUP, len(mid_c) - MAX_BARS - 1)

                def feat(name, arr, fn):
                    s_run, s_base = fn(arr[idxs]), fn(arr[base])
                    print(f"             {name:<22} run {s_run:7.3f}   base {s_base:7.3f}")
                    return {"run": float(s_run), "base": float(s_base)}

                print("           features at the run's FIRST bar vs any bar (medians):")
                feats = {}
                med = lambda v: float(np.nanmedian(v))
                feats["rsi7"] = feat("RSI(7)", r7, med)
                feats["atr_percentile"] = feat("ATR percentile", atr_pct, med)
                feats["bb_width_atr"] = feat("BB width / ATR", width, med)
                feats["vol_ratio"] = feat("volume / SMA20", np.divide(vol, vsma, out=np.ones_like(vol), where=vsma > 0), med)
                feats["dist_ema50_atr"] = feat("(close-EMA50)/ATR", np.divide(mid_c - e50, a, out=np.zeros_like(a), where=a > 0), med)
                feats["dist_ema200_atr"] = feat("(close-EMA200)/ATR", np.divide(mid_c - e200, a, out=np.zeros_like(a), where=a > 0), med)
                feats["room_to_swing_hi"] = feat("(swingHi-close)/ATR", np.divide(swing_hi - mid_c, a, out=np.zeros_like(a), where=a > 0), med)
                feats["room_to_swing_lo"] = feat("(close-swingLo)/ATR", np.divide(mid_c - swing_lo, a, out=np.zeros_like(a), where=a > 0), med)
                hours = bars.index.hour.to_numpy()
                hr_run = np.bincount(hours[idxs], minlength=24) / len(idxs)
                hr_base = np.bincount(hours[base], minlength=24) / len(base)
                lift = np.divide(hr_run, hr_base, out=np.zeros_like(hr_run), where=hr_base > 0)
                top = np.argsort(-lift)[:5]
                feats["hour_lift_top5"] = {int(h): round(float(lift[h]), 2) for h in top}
                print(f"             hour-of-day lift (top 5): "
                      + ", ".join(f"{h:02d}:00 x{lift[h]:.2f}" for h in top))
                results["epics"][epic]["features_discovery"] = feats

    OUT.write_text(json.dumps(results, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
