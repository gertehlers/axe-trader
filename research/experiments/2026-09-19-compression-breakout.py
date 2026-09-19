"""H-0008: the volatility-compression breakout, run exactly as pre-registered.

Spec: docs/superpowers/specs/2026-09-19-compression-breakout-design.md
Criteria: research/ledger/H-0008-compression-breakout.md  (committed BEFORE this ran)

The existing simulator fills at the next bar's open, which cannot express a resting stop order at
a price level, so fills are computed here directly from 1-minute bars.

Cost model, stated explicitly:
  LONG  enters at range_high + slip, exits at exit_mid - slip
  SHORT enters at range_low  - slip, exits at exit_mid + slip
  the full bid/ask spread at the entry minute is charged once as a round trip
  financing is exactly 0: sessions 00-09 UTC with a <=2.25h hold never cross the 21:00 charge

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-compression-breakout.py
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

TIMEFRAME = "15min"
SLICES = {
    "discovery":  (pd.Timestamp("2024-01-01T00:00:00Z"), pd.Timestamp("2025-09-30T23:59:00Z")),
    "validation": (pd.Timestamp("2025-10-01T00:00:00Z"), pd.Timestamp("2026-07-31T23:59:00Z")),
    "holdout":    (pd.Timestamp("2026-08-01T00:00:00Z"), pd.Timestamp("2026-12-31T23:59:00Z")),
}
# every one of these is fixed by the spec and may not be tuned here
ATR_PCT_MAX, VOL_MULT = 0.40, 1.2
SESSION_HOURS = [0, 1, 7, 8, 9]
RANGE_BARS, ENTRY_WINDOW_BARS, TIME_STOP_BARS = 10, 3, 9
MIN_RANGE_ATR = 0.5
TICK = 0.001
SLIP_TICKS_PRIMARY = 10
SLIP_SENSITIVITY = [0, 5, 10, 20, 40]
WARMUP = 600
PLACEBO_SETS = 200
SEED = 20260919


def breakout_outcome(i, sig, minutes_by_bar, mid_c, mid_h, mid_l, atr, slip):
    """The trade this setup bar produces, or None. Pure function of data up to and after bar i."""
    if i - RANGE_BARS + 1 < 0 or i + ENTRY_WINDOW_BARS + TIME_STOP_BARS + 1 >= len(mid_c):
        return None
    a = atr[i]
    if not a or np.isnan(a) or a <= 0:
        return None
    rng_hi = mid_h[i - RANGE_BARS + 1:i + 1].max()
    rng_lo = mid_l[i - RANGE_BARS + 1:i + 1].min()
    width = rng_hi - rng_lo
    if width < MIN_RANGE_ATR * a:            # spec guard
        return None

    for j in range(i + 1, i + 1 + ENTRY_WINDOW_BARS):
        mins = minutes_by_bar.get(j)
        if mins is None:
            continue
        mh, ml, mbid, mask_ = mins
        for m in range(len(mh)):
            hit_long = mh[m] >= rng_hi
            hit_short = ml[m] <= rng_lo
            if hit_long and hit_short:
                return None                  # intrabar order unknowable — spec says skip
            if not (hit_long or hit_short):
                continue
            side = 1 if hit_long else -1
            entry = (rng_hi + slip) if hit_long else (rng_lo - slip)
            stop = rng_lo if hit_long else rng_hi
            spread = float(mask_[m] - mbid[m])
            stop_dist = abs(entry - stop)
            if stop_dist <= 0:
                return None
            # Walk forward MINUTE by minute from the minute AFTER the fill.
            # Checking the stop against the entry bar's own 15m low/high is a lookahead bug: a bar
            # that breaks up out of a range has its low back inside the range, from price action
            # that happened BEFORE the fill. It stopped 72% of trades out on movement that had
            # already occurred. The scan therefore starts at m+1 within the entry bar.
            exit_bar, exit_mid, reason = None, None, None
            last_bar = min(j + TIME_STOP_BARS, len(mid_c) - 1)
            for k in range(j, last_bar + 1):
                kmins = minutes_by_bar.get(k)
                if kmins is not None:
                    kh, kl, _, _ = kmins
                    start = m + 1 if k == j else 0
                    for mm in range(start, len(kh)):
                        if side > 0 and kl[mm] <= stop:
                            exit_bar, exit_mid, reason = k, stop, "STOP"; break
                        if side < 0 and kh[mm] >= stop:
                            exit_bar, exit_mid, reason = k, stop, "STOP"; break
                    if exit_bar is not None:
                        break
                if k == last_bar:
                    exit_bar, exit_mid, reason = k, mid_c[k], "TIME"
            if exit_bar is None:
                return None
            exit_px = (exit_mid - slip) if side > 0 else (exit_mid + slip)
            gross = (exit_px - entry) if side > 0 else (entry - exit_px)
            net = gross - spread
            return {"setup_bar": int(i), "entry_bar": int(j), "exit_bar": int(exit_bar),
                    "side": int(side), "entry": float(entry), "stop": float(stop),
                    "exit": float(exit_px), "reason": reason,
                    "stop_dist": float(stop_dist), "gross_pts": float(gross),
                    "spread_pts": float(spread), "net_pts": float(net),
                    "r": float(net / stop_dist)}
    return None


def bootstrap_ci(values, iterations=10_000, seed=SEED):
    rng = np.random.default_rng(seed)
    v = np.asarray(values, dtype=float)
    if len(v) < 2:
        return (float("nan"), float("nan"))
    draws = rng.integers(0, len(v), size=(iterations, len(v)))
    means = v[draws].mean(axis=1)
    return (float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)))


def run_slice(bars, minutes, slip):
    mid_c = ((bars["close_bid"] + bars["close_ask"]) / 2).to_numpy()
    mid_h = ((bars["high_bid"] + bars["high_ask"]) / 2).to_numpy()
    mid_l = ((bars["low_bid"] + bars["low_ask"]) / 2).to_numpy()
    vol = bars["volume"].to_numpy(dtype=float)
    atr = atr_ind(mid_h, mid_l, mid_c, 14)
    atr_pct = pd.Series(atr).rolling(500, min_periods=100).rank(pct=True).to_numpy()
    vsma = sma(vol, 20)
    vol_ratio = np.divide(vol, vsma, out=np.ones_like(vol), where=vsma > 0)
    hours = bars.index.hour.to_numpy()

    # minute bars grouped by the signal bar that contains them
    step = pd.Timedelta(TIMEFRAME)
    m_mid_h = ((minutes["high_bid"] + minutes["high_ask"]) / 2).to_numpy()
    m_mid_l = ((minutes["low_bid"] + minutes["low_ask"]) / 2).to_numpy()
    m_bid = minutes["close_bid"].to_numpy()
    m_ask = minutes["close_ask"].to_numpy()
    # Map each minute to the bar that CONTAINS it by searching the real bar index.
    # Dividing elapsed time by the bar width assumes a contiguous series; oil closes overnight and
    # at weekends, so validation holds 19,636 bars where a gapless span would hold ~29,000. That
    # arithmetic attached minutes to the wrong bars entirely.
    pos = bars.index.searchsorted(minutes.index, side="right") - 1
    within = (pos >= 0) & (pos < len(bars))
    within[within] &= (minutes.index[within] < bars.index[pos[within]] + step)
    minutes_by_bar = {}
    order = np.argsort(pos[within], kind="stable")
    pw, hw, lw, bw2, aw = (pos[within][order], m_mid_h[within][order], m_mid_l[within][order],
                           m_bid[within][order], m_ask[within][order])
    edges = np.flatnonzero(np.diff(pw)) + 1
    for lo_i, hi_i in zip(np.r_[0, edges], np.r_[edges, len(pw)]):
        minutes_by_bar[int(pw[lo_i])] = (hw[lo_i:hi_i], lw[lo_i:hi_i],
                                         bw2[lo_i:hi_i], aw[lo_i:hi_i])

    in_hours = np.isin(hours, SESSION_HOURS)
    eligible = np.zeros(len(bars), dtype=bool)
    eligible[WARMUP:len(bars) - ENTRY_WINDOW_BARS - TIME_STOP_BARS - 2] = True
    hour_pool = np.flatnonzero(in_hours & eligible)
    setup = in_hours & eligible & (atr_pct < ATR_PCT_MAX) & (vol_ratio > VOL_MULT)
    setup_bars = np.flatnonzero(setup)

    # per-bar outcomes for every hour-matched bar — real and placebo share this table
    outcomes = {}
    for i in hour_pool:
        o = breakout_outcome(i, None, minutes_by_bar, mid_c, mid_h, mid_l, atr, slip)
        if o is not None:
            outcomes[int(i)] = o

    # the realistic sequential run: no new position while one is open
    seq, busy_until = [], -1
    for i in setup_bars:
        if i <= busy_until:
            continue
        o = outcomes.get(int(i))
        if o is None:
            continue
        seq.append(o)
        busy_until = o["exit_bar"]
    return {"setup_bars": setup_bars, "hour_pool": hour_pool, "outcomes": outcomes,
            "sequential": seq}


def main() -> None:
    rng = np.random.default_rng(SEED)
    results = {"spec": "docs/superpowers/specs/2026-09-19-compression-breakout-design.md",
               "slip_ticks_primary": SLIP_TICKS_PRIMARY, "slices": {}}

    for epic in ["OIL_CRUDE", "OIL_BRENT", "US500"]:
        minutes_all = load_cached_minutes(DB, epic, CACHE)
        bars_all = resample(minutes_all, TIMEFRAME)
        results["slices"][epic] = {}
        print(f"\n{'='*74}\n{epic}\n{'='*74}")

        for sname, (lo_t, hi_t) in SLICES.items():
            bars = bars_all[(bars_all.index >= lo_t) & (bars_all.index <= hi_t)]
            minutes = minutes_all[(minutes_all.index >= lo_t) & (minutes_all.index <= hi_t)]
            if len(bars) < WARMUP + 100:
                print(f"  {sname:11s} too few bars ({len(bars)})"); continue

            slip = SLIP_TICKS_PRIMARY * TICK
            res = run_slice(bars, minutes, slip)
            seq = res["sequential"]
            rs = np.array([t["r"] for t in seq]) if seq else np.array([])
            cell = {"n": len(rs), "setups": int(len(res["setup_bars"]))}
            if len(rs):
                lo_ci, hi_ci = bootstrap_ci(rs)
                cell.update({
                    "expectancy_r": float(rs.mean()), "median_r": float(np.median(rs)),
                    "win_rate": float((rs > 0).mean()), "ci95": [lo_ci, hi_ci],
                    "stops": int(sum(t["reason"] == "STOP" for t in seq)),
                    "times": int(sum(t["reason"] == "TIME" for t in seq)),
                    "long": int(sum(t["side"] > 0 for t in seq)),
                })
                print(f"  {sname:11s} setups {cell['setups']:5d} -> trades {len(rs):5d}  "
                      f"exp {rs.mean():+.4f}R  CI [{lo_ci:+.4f},{hi_ci:+.4f}]  "
                      f"win {(rs > 0).mean():5.1%}  STOP {cell['stops']}/TIME {cell['times']}")
            else:
                print(f"  {sname:11s} setups {cell['setups']:5d} -> no trades")

            # placebo: same mechanics at hour-matched bars WITHOUT quiet+volume
            if len(rs) >= 20:
                pool = np.array([i for i in res["hour_pool"] if int(i) in res["outcomes"]])
                per_bar = np.array([res["outcomes"][int(i)]["r"] for i in pool])
                real_mean = rs.mean()
                k = min(len(rs), len(pool))
                pl = np.array([per_bar[rng.integers(0, len(pool), size=k)].mean()
                               for _ in range(PLACEBO_SETS)])
                pct = float((pl < real_mean).mean() * 100)
                cell["placebo"] = {"pool": int(len(pool)), "mean": float(pl.mean()),
                                   "percentile": pct}
                print(f"              placebo pool {len(pool):5d} mean {pl.mean():+.4f}R "
                      f"-> percentile {pct:.1f}")
            results["slices"][epic][sname] = cell

        # slippage sensitivity, OIL_CRUDE validation only
        if epic == "OIL_CRUDE":
            bars = bars_all[(bars_all.index >= SLICES["validation"][0]) &
                            (bars_all.index <= SLICES["validation"][1])]
            minutes = minutes_all[(minutes_all.index >= SLICES["validation"][0]) &
                                  (minutes_all.index <= SLICES["validation"][1])]
            sens = {}
            print("  slippage sensitivity (validation):")
            for ticks in SLIP_SENSITIVITY:
                r2 = run_slice(bars, minutes, ticks * TICK)
                v = np.array([t["r"] for t in r2["sequential"]])
                sens[ticks] = {"n": int(len(v)),
                               "expectancy_r": float(v.mean()) if len(v) else None}
                print(f"      {ticks:3d} ticks  n={len(v):5d}  "
                      + (f"exp {v.mean():+.4f}R" if len(v) else "no trades"))
            results["slippage_sensitivity_oil_crude_validation"] = sens

    OUT.write_text(json.dumps(results, indent=2, default=str))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
