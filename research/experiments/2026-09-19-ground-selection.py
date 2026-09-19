"""Which instrument and timeframe should the next hypothesis point at?

The cost check (research/cost-reality/2026-09-19.md) answered half of it: how much of a typical
bar's range a round trip eats. It does not answer the other half, which is what has actually killed
every hypothesis since — whether the ground carries any directional structure at all. Cheap ground
with none is still untradeable, and three routes on OIL_CRUDE 15m (H-0006, H-0007, H-0008) have now
said direction is unavailable there.

Measured per instrument x timeframe:

  VR(q)   Lo-MacKinlay variance ratio. Var of a q-bar return over q x var of a 1-bar return.
          1.0 = random walk, nothing to extract. >1 trends, <1 mean-reverts.
  z*(q)   its heteroskedasticity-robust test statistic. |z| > 2 is a real deviation; anything
          less is a number to ignore however far VR sits from 1.
  bars    the sample, because a beautiful VR on 600 daily bars decides nothing (see H-0004).

Returns spanning a session gap are dropped: a move across an overnight close is not a q-bar move.

This measures the market, not a strategy, so nothing here can be overfitted and the holdout does
not apply -- the full history is used, exactly as the cost check did.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-ground-selection.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPICS = ["OIL_CRUDE", "US500", "OIL_BRENT"]          # NATURALGAS excluded, see EXCLUDED-INSTRUMENTS.md
TIMEFRAMES = ["5min", "15min", "1h", "4h", "1d"]
LAGS = [2, 4, 8, 16]
# cost/TR from research/cost-reality/2026-09-19.md, so the two halves sit in one table
COST_TR = {
    ("OIL_CRUDE", "1d"): 0.024, ("US500", "1d"): 0.030, ("US500", "4h"): 0.035,
    ("OIL_BRENT", "1d"): 0.040, ("OIL_CRUDE", "4h"): 0.050, ("US500", "1h"): 0.057,
    ("OIL_BRENT", "4h"): 0.058, ("OIL_CRUDE", "1h"): 0.102, ("US500", "15min"): 0.117,
    ("OIL_BRENT", "1h"): 0.120, ("US500", "5min"): 0.207, ("OIL_CRUDE", "15min"): 0.214,
    ("OIL_BRENT", "15min"): 0.249, ("OIL_CRUDE", "5min"): 0.382, ("OIL_BRENT", "5min"): 0.450,
}


def variance_ratio(r: np.ndarray, q: int):
    """Lo-MacKinlay VR(q) with the heteroskedasticity-robust z statistic."""
    r = np.asarray(r, dtype=float)
    n = len(r)
    if n < q * 10:
        return None, None
    mu = r.mean()
    d = r - mu
    var_1 = (d ** 2).sum() / (n - 1)
    if var_1 <= 0:
        return None, None
    # q-bar overlapping sums
    c = np.cumsum(np.r_[0.0, r])
    q_sums = c[q:] - c[:-q]
    m = q * (n - q + 1) * (1 - q / n)
    var_q = ((q_sums - q * mu) ** 2).sum() / m
    vr = var_q / var_1
    # robust variance of VR
    denom = (d ** 2).sum() ** 2
    theta = 0.0
    for j in range(1, q):
        num = ((d[j:] ** 2) * (d[:-j] ** 2)).sum()
        delta = num / denom if denom > 0 else 0.0
        theta += ((2.0 * (q - j) / q) ** 2) * delta
    z = (vr - 1.0) / np.sqrt(theta) if theta > 0 else None
    return float(vr), (float(z) if z is not None else None)


def contiguous_log_returns(bars: pd.DataFrame, tf: str) -> np.ndarray:
    """Log returns, dropping any that span a session gap."""
    mid = ((bars["close_bid"] + bars["close_ask"]) / 2.0).to_numpy()
    step = pd.Timedelta("1d") if tf == "1d" else pd.Timedelta(tf)
    gaps = bars.index.to_series().diff().to_numpy()
    r = np.diff(np.log(mid))
    ok = gaps[1:] <= np.timedelta64(int(step.total_seconds() * 1.5), "s")
    return r[ok]


def main() -> None:
    rows = []
    for epic in EPICS:
        minutes = load_cached_minutes(DB, epic, CACHE)
        for tf in TIMEFRAMES:
            bars = resample(minutes, tf)
            if len(bars) < 200:
                continue
            r = contiguous_log_returns(bars, tf)
            row = {"epic": epic, "tf": tf, "bars": int(len(bars)), "returns": int(len(r)),
                   "cost_tr": COST_TR.get((epic, tf))}
            for q in LAGS:
                vr, z = variance_ratio(r, q)
                row[f"vr{q}"] = vr
                row[f"z{q}"] = z
            rows.append(row)

    df = pd.DataFrame(rows)
    print(f"{'instrument':<11} {'tf':>5} {'bars':>8} {'cost/TR':>8}  "
          + "  ".join(f"VR{q:<2}      z{q:<2}" for q in LAGS))
    print("-" * 104)
    for _, x in df.iterrows():
        line = f"{x.epic:<11} {x.tf:>5} {x.bars:>8,} "
        line += f"{x.cost_tr:>7.1%}  " if x.cost_tr is not None else f"{'—':>8}  "
        for q in LAGS:
            vr, z = x[f"vr{q}"], x[f"z{q}"]
            if vr is None:
                line += f"{'—':>7} {'—':>8}  "
            else:
                star = "*" if (z is not None and abs(z) > 2) else " "
                line += f"{vr:>7.3f} {z:>+7.2f}{star} "
        print(line)

    print("\n* = |z| > 2, a real deviation from a random walk.")
    print("VR > 1 trends (momentum is available); VR < 1 mean-reverts; ~1.0 means nothing is there.")

    # the joint ranking: structure that is real, on ground that is cheap
    print("\n=== grounds with a significant deviation at any lag, cheapest first ===")
    cand = []
    for _, x in df.iterrows():
        best = None
        for q in LAGS:
            z = x[f"z{q}"]
            if z is not None and abs(z) > 2:
                if best is None or abs(z) > abs(best[1]):
                    best = (q, z, x[f"vr{q}"])
        if best and x.cost_tr is not None:
            cand.append((x.cost_tr, x.epic, x.tf, best, int(x.bars)))
    for cost, epic, tf, (q, z, vr) in [(c[0], c[1], c[2], c[3]) for c in sorted(cand)]:
        bars_n = [c[4] for c in cand if c[1] == epic and c[2] == tf][0]
        kind = "trending" if vr > 1 else "mean-reverting"
        print(f"  cost/TR {cost:>5.1%}  {epic:<11} {tf:<5} {bars_n:>8,} bars  "
              f"strongest VR({q})={vr:.3f} z={z:+.2f}  {kind}")
    if not cand:
        print("  none — no ground shows a significant deviation from a random walk")

    OUT.write_text(json.dumps(rows, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
