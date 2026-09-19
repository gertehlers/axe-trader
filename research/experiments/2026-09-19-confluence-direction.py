"""H-0006: does the confluence entry point the wrong way on OIL_CRUDE?

Criteria are pre-registered in research/ledger/H-0006-confluence-entry-direction.md and were
committed before this ran. Fading is negation, so the claim is stated on the as-signalled measure:
the LONG entry's forward return sits BELOW matched random entries.

Primary: OIL_CRUDE LONG, 4h (16 bars), development window, percentile <= 5.0 against 200
whole-week shifts, a negative median, and an edge above the 0.0351 round-trip spread.

Also runs the owner's two observations as diagnostics with no pass/fail: whether the entry is late
(prior-move size) or lands in chop (forward efficiency ratio), and how much of the available move
the exit arms actually capture.

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-confluence-direction.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.diagnose import excursions, forward_returns, percentile_of
from engine.pillars import PillarConfig, compute_pillars

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPIC = "OIL_CRUDE"
TIMEFRAME = "15min"
DEV_START = pd.Timestamp("2024-01-01T00:00:00Z")
DEV_END = pd.Timestamp("2026-07-31T23:59:00Z")
HORIZONS = [1, 4, 8, 16, 32]           # 15m, 1h, 2h, 4h, 8h
PRIMARY_HORIZON = 16                   # 4h
PLACEBO_SETS = 200
SHIFT_WEEKS = 26
WARMUP = 600
SPREAD_PTS = 0.0351
PRIOR_BARS = 8                         # the 2h run-up before an entry


def efficiency_ratio(mid: np.ndarray, entries: np.ndarray, horizon: int) -> np.ndarray:
    """Net displacement / summed absolute bar moves over the forward window.

    1.0 is a straight line; near 0 is chop. This is the owner's "choppy waters" made measurable.
    """
    usable = entries[entries + horizon < len(mid)]
    if len(usable) == 0:
        return np.array([])
    offsets = np.arange(0, horizon + 1)
    window = mid[usable[:, None] + offsets[None, :]]
    net = np.abs(window[:, -1] - window[:, 0])
    path = np.abs(np.diff(window, axis=1)).sum(axis=1)
    return np.divide(net, path, out=np.zeros_like(net), where=path > 0)


def prior_move(mid: np.ndarray, atr: np.ndarray, entries: np.ndarray, bars: int) -> np.ndarray:
    """Signed move over the `bars` before the entry, in ATR units. Large = the signal is late."""
    usable = entries[entries - bars >= 0]
    if len(usable) == 0:
        return np.array([])
    a = atr[usable]
    return np.divide(mid[usable] - mid[usable - bars], a, out=np.zeros_like(a), where=a > 0)


def main() -> None:
    rng = np.random.default_rng(20260919)
    shifts = rng.choice(np.concatenate([np.arange(-SHIFT_WEEKS, 0), np.arange(1, SHIFT_WEEKS + 1)]),
                        size=PLACEBO_SETS, replace=True)
    week = np.timedelta64(7 * 24 * 60, "m")

    full = resample(load_cached_minutes(DB, EPIC, CACHE), TIMEFRAME)
    dev = full[(full.index >= DEV_START) & (full.index <= DEV_END)]
    holdout = full[full.index > DEV_END]
    print(f"{EPIC} {TIMEFRAME}: dev {dev.index[0].date()} -> {dev.index[-1].date()} "
          f"({len(dev):,} bars) · holdout {len(holdout):,} bars")

    results = {"epic": EPIC, "timeframe": TIMEFRAME, "spread_pts": SPREAD_PTS,
               "primary_horizon_bars": PRIMARY_HORIZON, "placebo_sets": PLACEBO_SETS,
               "dev": {}, "holdout": {}, "diagnostics": {}}

    votes = compute_pillars(dev, PillarConfig())
    mid = ((dev["close_bid"] + dev["close_ask"]) / 2.0).to_numpy()
    high = ((dev["high_bid"] + dev["high_ask"]) / 2.0).to_numpy()
    low = ((dev["low_bid"] + dev["low_ask"]) / 2.0).to_numpy()
    warm = np.zeros(len(dev), dtype=bool); warm[WARMUP:] = True
    times = dev.index.to_numpy().astype("datetime64[m]")
    order = np.argsort(times); sorted_times = times[order]

    entry = {"LONG": warm & (votes.bullish_score >= 3) & votes.long_gate,
             "SHORT": warm & (votes.bearish_score >= 3) & votes.short_gate}

    for side, mask in entry.items():
        entries = np.flatnonzero(mask)
        sign = 1.0 if side == "LONG" else -1.0
        rec = {"entries": int(len(entries)), "horizons": {}}
        print(f"\n=== {side}: {len(entries)} entries (dev) ===")
        for horizon in HORIZONS:
            real = sign * forward_returns(mid, entries, horizon)
            placebo = np.full(PLACEBO_SETS, np.nan)
            for i, weeks in enumerate(shifts):
                wanted = times[entries] + weeks * week
                found = np.clip(np.searchsorted(sorted_times, wanted), 0, len(sorted_times) - 1)
                exact = sorted_times[found] == wanted
                shifted = order[found[exact]]
                if len(shifted) < 0.8 * len(entries):
                    continue
                placebo[i] = (sign * forward_returns(mid, shifted, horizon)).mean()
            usable = ~np.isnan(placebo)
            pct = percentile_of(float(real.mean()), placebo[usable])
            cell = {"mean": float(real.mean()), "median": float(np.median(real)),
                    "win_rate": float((real > 0).mean()),
                    "placebo_mean": float(placebo[usable].mean()),
                    "edge": float(real.mean() - placebo[usable].mean()),
                    "percentile": pct, "placebo_sets_used": int(usable.sum()), "n": int(len(real))}
            rec["horizons"][f"{horizon}bar"] = cell
            flag = "  <-- PRIMARY" if (horizon == PRIMARY_HORIZON and side == "LONG") else ""
            print(f"  {horizon:>2}bar mean {cell['mean']:+8.4f} median {cell['median']:+8.4f} "
                  f"win {cell['win_rate']:5.1%} placebo {cell['placebo_mean']:+7.4f} "
                  f"edge {cell['edge']:+7.4f} pct {pct:5.1f}{flag}")
        results["dev"][side] = rec

    # --- the pre-registered primary test -------------------------------------------------
    cell = results["dev"]["LONG"]["horizons"][f"{PRIMARY_HORIZON}bar"]
    c1 = cell["percentile"] <= 5.0 and cell["placebo_sets_used"] >= 150
    c2 = cell["median"] < 0
    c3 = abs(cell["edge"]) > SPREAD_PTS
    verdict = {"percentile_le_5": bool(c1), "median_negative": bool(c2),
               "edge_beats_spread": bool(c3), "passes": bool(c1 and c2 and c3)}
    results["primary"] = {**cell, **verdict}
    print("\n=== PRIMARY (LONG, 4h, dev) ===")
    print(f"  1. percentile {cell['percentile']:.1f} <= 5.0 over {cell['placebo_sets_used']} sets : {c1}")
    print(f"  2. median {cell['median']:+.4f} < 0                                  : {c2}")
    print(f"  3. |edge| {abs(cell['edge']):.4f} > spread {SPREAD_PTS}                    : {c3}")
    print(f"  --> {'PASSES' if verdict['passes'] else 'FAILS'}")

    # --- holdout, reported for direction only --------------------------------------------
    if len(holdout) > WARMUP:
        hv = compute_pillars(holdout, PillarConfig())
        hmid = ((holdout["close_bid"] + holdout["close_ask"]) / 2.0).to_numpy()
        hwarm = np.zeros(len(holdout), dtype=bool); hwarm[WARMUP:] = True
        for side in ["LONG", "SHORT"]:
            m = hwarm & ((hv.bullish_score >= 3) & hv.long_gate if side == "LONG"
                         else (hv.bearish_score >= 3) & hv.short_gate)
            e = np.flatnonzero(m)
            sign = 1.0 if side == "LONG" else -1.0
            r = sign * forward_returns(hmid, e, PRIMARY_HORIZON)
            results["holdout"][side] = {"entries": int(len(e)), "n": int(len(r)),
                "mean": float(r.mean()) if len(r) else None,
                "median": float(np.median(r)) if len(r) else None,
                "win_rate": float((r > 0).mean()) if len(r) else None}
            print(f"  holdout {side}: n={len(r)} "
                  + (f"mean {r.mean():+.4f} median {np.median(r):+.4f} win {(r > 0).mean():.1%}"
                     if len(r) else "no entries"))

    # --- diagnostics: is the entry late, and does it land in chop? -----------------------
    print("\n=== diagnostics (no pass/fail) ===")
    atr = votes.atr
    eligible = np.flatnonzero(warm)
    for side, mask in entry.items():
        entries = np.flatnonzero(mask)
        sign = 1.0 if side == "LONG" else -1.0
        pm = sign * prior_move(mid, atr, entries, PRIOR_BARS)
        pm_all = sign * prior_move(mid, atr, eligible, PRIOR_BARS)
        er = efficiency_ratio(mid, entries, PRIMARY_HORIZON)
        er_all = efficiency_ratio(mid, eligible, PRIMARY_HORIZON)
        mfe, mae = excursions(high, low, mid, entries, PRIMARY_HORIZON)
        if side == "SHORT":
            mfe, mae = mae, mfe          # a SHORT's favourable excursion is the low side
        a = atr[entries[entries + PRIMARY_HORIZON < len(mid)]]
        d = {
            "prior_move_atr_median": float(np.median(pm)),
            "prior_move_atr_median_baseline": float(np.median(pm_all)),
            "prior_move_share_above_1atr": float((pm > 1.0).mean()),
            "forward_efficiency_median": float(np.median(er)),
            "forward_efficiency_median_baseline": float(np.median(er_all)),
            "mfe_atr_median": float(np.median(mfe / a)),
            "mae_atr_median": float(np.median(mae / a)),
        }
        results["diagnostics"][side] = d
        print(f"  {side}: prior {PRIOR_BARS}-bar move {d['prior_move_atr_median']:+.3f} ATR "
              f"(baseline {d['prior_move_atr_median_baseline']:+.3f}), "
              f"{d['prior_move_share_above_1atr']:.1%} came after a >1 ATR run")
        print(f"         forward efficiency {d['forward_efficiency_median']:.3f} "
              f"(baseline {d['forward_efficiency_median_baseline']:.3f}) — 1.0 straight, 0 chop")
        print(f"         available in 4h: MFE {d['mfe_atr_median']:+.2f} ATR, "
              f"MAE {d['mae_atr_median']:+.2f} ATR (median)")

    OUT.write_text(json.dumps(results, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
