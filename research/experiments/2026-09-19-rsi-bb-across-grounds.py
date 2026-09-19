"""Does the RSI+Bollinger mean-reversion signal ever clear its own spread?

Follows the OIL_BRENT 5m run analysis (2026-09-19-brent-5m-run-ta.py). That found the signal has
REAL directional content -- 93-95% of >=6-bar runs starting at those conditions are up-runs, and
forward returns are positive in both slices -- but the edge sits inside the spread.

The edge is roughly constant in ATR terms across timeframes while cost/ATR falls as the bars get
slower, so in principle slow ground should win. This tests that directly.

  signal   LONG  when RSI(7) < 30 AND close <= lower Bollinger(20, 2.0)
           SHORT when RSI(7) > 70 AND close >= upper Bollinger(20, 2.0)
  edge     mean forward move in the signalled direction, in ATR(14) units at the signal bar
  cost     the round-trip spread from research/cost-reality/2026-09-19.md, in the same ATR units
  ratio    edge / cost. Above 1.0 the signal pays for its own spread.

EXPLORATORY. The horizon is chosen as the best of four per cell, which inflates every number --
a cell must therefore clear comfortably AND in both slices to mean anything.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-rsi-bb-across-grounds.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.indicators import atr as atr_ind, bollinger, rsi

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

SPREAD = {"OIL_CRUDE": 0.0351, "OIL_BRENT": 0.0412, "US500": 0.5600}
SLICES = {"discovery": ("2024-01-01", "2025-09-30"), "validation": ("2025-10-01", "2026-07-31")}
GRIDS = [("5min", [6, 12, 24, 48]), ("15min", [4, 8, 16, 32]), ("1h", [2, 4, 8, 16]),
         ("4h", [1, 2, 4, 8]), ("1d", [1, 2, 3, 5])]


def main() -> None:
    rows = []
    for epic in ["OIL_CRUDE", "OIL_BRENT", "US500"]:
        minutes = load_cached_minutes(DB, epic, CACHE)
        for tf, horizons in GRIDS:
            bars_all = resample(minutes, tf)
            for sname, (lo, hi) in SLICES.items():
                b = bars_all[(bars_all.index >= pd.Timestamp(lo, tz="UTC")) &
                             (bars_all.index <= pd.Timestamp(hi, tz="UTC"))]
                if len(b) < 800:
                    continue
                h = ((b["high_bid"] + b["high_ask"]) / 2).to_numpy()
                l = ((b["low_bid"] + b["low_ask"]) / 2).to_numpy()
                c = ((b["close_bid"] + b["close_ask"]) / 2).to_numpy()
                a = atr_ind(h, l, c, 14)
                safe = np.where(a > 0, a, np.nan)
                med_atr = float(np.nanmedian(a))
                if not med_atr or np.isnan(med_atr):
                    continue
                r7 = rsi(c, 7)
                _, bb_u, bb_l = bollinger(c, 20, 2.0)
                n = len(c)
                warm = min(600, n // 4)
                elig = np.zeros(n, dtype=bool)
                elig[warm:n - max(horizons) - 1] = True
                longs = np.flatnonzero((r7 < 30) & (c <= bb_l) & elig)
                shorts = np.flatnonzero((r7 > 70) & (c >= bb_u) & elig)
                if len(longs) + len(shorts) < 100:
                    continue
                best = None
                per_h = {}
                for k in horizons:
                    f = np.concatenate([(c[longs + k] - c[longs]) / safe[longs],
                                        -(c[shorts + k] - c[shorts]) / safe[shorts]])
                    m = float(np.nanmean(f))
                    per_h[k] = m
                    if best is None or m > best[0]:
                        best = (m, k)
                edge, k = best
                cost = SPREAD[epic] / med_atr
                rows.append({"epic": epic, "tf": tf, "slice": sname,
                             "n": int(len(longs) + len(shorts)), "edge_atr": edge,
                             "best_horizon_bars": int(k), "cost_atr": float(cost),
                             "edge_over_cost": float(edge / cost), "by_horizon": per_h})

    df = pd.DataFrame([{k: v for k, v in r.items() if k != "by_horizon"} for r in rows])
    piv = df.pivot_table(index=["epic", "tf"], columns="slice", values="edge_over_cost")
    print(piv.round(2).to_string())
    print("\nboth slices above 1.0 (the only thing that would count):")
    both = piv[(piv.get("discovery", 0) > 1.0) & (piv.get("validation", 0) > 1.0)]
    print("  none" if both.empty else both.round(2).to_string())
    print("\nboth slices positive at all (a weaker, directional consistency):")
    pos = piv[(piv.get("discovery", 0) > 0) & (piv.get("validation", 0) > 0)]
    print("  none" if pos.empty else pos.round(2).to_string())
    OUT.write_text(json.dumps(rows, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
