"""Step 2 of the run census: does anything at a run's start actually PREDICT it?

CALIBRATION NOTE: the first pass used >=4 ATR within 28 bars, taken from the owner's marks' size
and outer duration. That was wrong: it is a 50% base rate, i.e. the market's normal state. His
marks are FAST -- median 5.1 ATR in 9 bars, 0.57 ATR/bar. At >=5 ATR within 9 bars the base rate
is 6.8%, which is an event. That is the definition used here.

The census showed runs begin at lower-than-usual volatility, on above-average volume, clustered at
session opens. That is measured by looking BACK from known runs, which proves nothing: it is
P(feature | run), and a trade needs P(run | feature).

This computes the forward lift, and fixes a bias in the census: that scan took the FURTHEST
displacement inside the 4..28 bar window, so its durations piled up at the cap. Here a run is the
FIRST bar count at which the move qualifies, which is also when a trader would know.

Discovery is where the rule may be chosen. Validation is looked at once, at the end. The holdout
after 2026-07-31 is not touched.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-run-lift.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.indicators import atr as atr_ind, sma

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
MIN_ATR, MIN_BARS, MAX_BARS = 5.0, 2, 9   # the owner's median mark: 5.1 ATR in 9 bars
WARMUP, LOOKAHEAD = 600, 2
SESSION_HOURS = {0, 1, 7, 8, 9}


def run_starts(close, high, low, atr):
    """Mark every bar at which a >=MIN_ATR move first qualifies within MAX_BARS. Overlapping."""
    n = len(close)
    starts = np.zeros(n, dtype=bool)
    direction = np.zeros(n, dtype=int)
    mae_at = np.full(n, np.nan)
    for i in range(WARMUP, n - MAX_BARS - 1):
        a = atr[i]
        if not a or np.isnan(a) or a <= 0:
            continue
        for k in range(MIN_BARS, MAX_BARS + 1):
            disp = (close[i + k] - close[i]) / a
            if abs(disp) >= MIN_ATR:
                d = 1 if disp > 0 else -1
                seg_hi = high[i + 1:i + k + 1].max()
                seg_lo = low[i + 1:i + k + 1].min()
                mae_at[i] = (close[i] - seg_lo) / a if d > 0 else (seg_hi - close[i]) / a
                starts[i] = True
                direction[i] = d
                break                      # FIRST qualifying k, not the furthest
    return starts, direction, mae_at


def main() -> None:
    results = {}
    for epic in EPICS:
        full = resample(load_cached_minutes(DB, epic, CACHE), TIMEFRAME)
        results[epic] = {}
        print(f"\n{'='*72}\n{epic}\n{'='*72}")
        for slice_name, (lo_t, hi_t) in SLICES.items():
            bars = full[(full.index >= lo_t) & (full.index <= hi_t)]
            c = ((bars["close_bid"] + bars["close_ask"]) / 2).to_numpy()
            h = ((bars["high_bid"] + bars["high_ask"]) / 2).to_numpy()
            l = ((bars["low_bid"] + bars["low_ask"]) / 2).to_numpy()
            v = bars["volume"].to_numpy(dtype=float)
            a = atr_ind(h, l, c, 14)
            starts, direction, mae_at = run_starts(c, h, l, a)

            atr_pct = pd.Series(a).rolling(500, min_periods=100).rank(pct=True).to_numpy()
            vsma = sma(v, 20)
            vol_ratio = np.divide(v, vsma, out=np.ones_like(v), where=vsma > 0)
            hours = bars.index.hour.to_numpy()

            eligible = np.zeros(len(c), dtype=bool)
            eligible[WARMUP:len(c) - MAX_BARS - 1] = True

            # "a run starts within LOOKAHEAD bars of here"
            soon = np.zeros(len(c), dtype=bool)
            idx = np.flatnonzero(starts)
            for off in range(LOOKAHEAD + 1):
                j = idx - off
                soon[j[j >= 0]] = True

            base = soon[eligible].mean()
            conds = {
                "quiet (ATR pct < 0.40)":        (atr_pct < 0.40),
                "volume > 1.2x SMA20":           (vol_ratio > 1.2),
                "session open (00,01,07,08,09)": np.isin(hours, list(SESSION_HOURS)),
            }
            conds["ALL THREE"] = conds["quiet (ATR pct < 0.40)"] & conds["volume > 1.2x SMA20"] & conds["session open (00,01,07,08,09)"]

            print(f"  {slice_name}: {len(bars):,} bars · {starts.sum()} run starts · "
                  f"base rate {base:.2%}")
            cell = {"bars": int(len(bars)), "run_starts": int(starts.sum()),
                    "base_rate": float(base), "conditions": {}}
            for name, m in conds.items():
                sel = m & eligible
                if sel.sum() < 30:
                    print(f"     {name:<32} too few bars ({sel.sum()})"); continue
                p = soon[sel].mean()
                cell["conditions"][name] = {"n": int(sel.sum()), "hit_rate": float(p),
                                            "lift": float(p / base) if base else None}
                print(f"     {name:<32} n={sel.sum():6,}  P(run soon)={p:6.2%}  lift x{p/base:.2f}")
            # direction is the problem: does the filter say WHICH way?
            sel = conds["ALL THREE"] & eligible & starts
            if sel.sum() >= 20:
                up = (direction[sel] > 0).mean()
                cell["direction_long_share_when_fired"] = float(up)
                print(f"     of those that ran: {up:.1%} LONG / {1-up:.1%} SHORT  "
                      f"(a coin flip means the filter gives timing, not direction)")
            results[epic][slice_name] = cell

    OUT.write_text(json.dumps(results, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
