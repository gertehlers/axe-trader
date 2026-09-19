"""OIL_BRENT 5m: find every run of 6+ bars, then ask what the TA looked like where it started.

The owner's request. Note the ground: OIL_BRENT 5m is rank 15 of 15 on cost
(research/cost-reality/2026-09-19.md) at 45.0% of a typical bar's range per round trip, and the
variance-ratio work found no unconditional structure there. This is a CONDITIONAL question, which
a variance ratio cannot rule out, so it is worth asking -- but anything found must eventually clear
that 45%.

EXPLORATORY. Runs are located by looking at the future, and the TA battery is then scored against
them, so every number here is hypothesis generation, not evidence. Discovery is where candidates
may be found; validation is checked once at the end; the holdout is untouched.

Runs are legs of an ATR-threshold ZigZag: a pivot is confirmed when price retraces REVERSAL_ATR
from an extreme, and the leg between two pivots is the run. Legs of >= MIN_RUN_BARS are kept.
The leg's first bar is the "perfect" entry; entries 1..3 bars later are scored too, because nobody
enters at the turn.

The battery is scored by DIRECTION, because that is what every previous attempt has failed on:
a condition is only useful if P(up-run | condition) differs from P(down-run | condition).

Run:
    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-09-19-brent-5m-run-ta.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.candles import (bearish_engulfing, bearish_harami, bullish_engulfing, bullish_harami,
                            hammer, shooting_star)
from engine.indicators import (adx, atr as atr_ind, bollinger, ema, highest, lowest, minus_di,
                               plus_di, rsi, sma)

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data" / "axe-trader.sqlite"
CACHE = ROOT / "research" / "engine" / ".cache"
OUT = Path(__file__).with_suffix(".json")

EPIC, TIMEFRAME = "OIL_BRENT", "5min"
SLICES = {
    "discovery":  (pd.Timestamp("2024-01-01T00:00:00Z"), pd.Timestamp("2025-09-30T23:59:00Z")),
    "validation": (pd.Timestamp("2025-10-01T00:00:00Z"), pd.Timestamp("2026-07-31T23:59:00Z")),
}
REVERSAL_ATR = 2.0
MIN_RUN_BARS = 6
ENTRY_TOLERANCE = 1      # the condition may fire on the pivot bar or the one before
WARMUP = 600


def zigzag_legs(high, low, close, atr, reversal_atr=REVERSAL_ATR):
    """ATR-threshold ZigZag. Returns confirmed legs as (start, end, direction)."""
    n = len(close)
    legs = []
    piv_i, piv_p, direction = WARMUP, close[WARMUP], 0
    ext_i, ext_p = piv_i, piv_p
    for i in range(WARMUP + 1, n):
        a = atr[i]
        if not a or np.isnan(a) or a <= 0:
            continue
        thr = reversal_atr * a
        if direction >= 0 and high[i] > ext_p:
            ext_i, ext_p = i, high[i]
        if direction <= 0 and low[i] < ext_p:
            ext_i, ext_p = i, low[i]
        if direction >= 0 and ext_p - low[i] >= thr and ext_i > piv_i:
            legs.append((piv_i, ext_i, 1))
            piv_i, piv_p, direction = ext_i, ext_p, -1
            ext_i, ext_p = i, low[i]
        elif direction <= 0 and high[i] - ext_p >= thr and ext_i > piv_i:
            legs.append((piv_i, ext_i, -1))
            piv_i, piv_p, direction = ext_i, ext_p, 1
            ext_i, ext_p = i, high[i]
    return legs


def build_features(bars):
    o = ((bars["open_bid"] + bars["open_ask"]) / 2).to_numpy()
    h = ((bars["high_bid"] + bars["high_ask"]) / 2).to_numpy()
    l = ((bars["low_bid"] + bars["low_ask"]) / 2).to_numpy()
    c = ((bars["close_bid"] + bars["close_ask"]) / 2).to_numpy()
    v = bars["volume"].to_numpy(dtype=float)
    a = atr_ind(h, l, c, 14)
    safe = np.where(a > 0, a, np.nan)

    r7, r14 = rsi(c, 7), rsi(c, 14)
    bb_m, bb_u, bb_l = bollinger(c, 20, 2.0)
    bb_w = (bb_u - bb_l) / safe
    bb_w_pct = pd.Series(bb_w).rolling(500, min_periods=100).rank(pct=True).to_numpy()
    atr_pct = pd.Series(a).rolling(500, min_periods=100).rank(pct=True).to_numpy()
    e20, e50, e200 = ema(c, 20), ema(c, 50), ema(c, 200)
    vr = np.divide(v, sma(v, 20), out=np.ones_like(v), where=sma(v, 20) > 0)
    adx14 = adx(h, l, c, 14)
    pdi, mdi = plus_di(h, l, c, 14), minus_di(h, l, c, 14)
    sw_hi, sw_lo = highest(c, 20), lowest(c, 20)
    prior8 = np.r_[np.full(8, np.nan), (c[8:] - c[:-8])] / safe

    f = {
        # --- mean-reversion flavour -------------------------------------------------
        "RSI(7) < 30":            r7 < 30,
        "RSI(7) > 70":            r7 > 70,
        "RSI(14) < 35":           r14 < 35,
        "RSI(14) > 65":           r14 > 65,
        "close <= lower BB":      c <= bb_l,
        "close >= upper BB":      c >= bb_u,
        "within 0.5 ATR of 20-bar low":  (c - sw_lo) / safe < 0.5,
        "within 0.5 ATR of 20-bar high": (sw_hi - c) / safe < 0.5,
        "prior 8 bars fell > 1 ATR":     prior8 < -1.0,
        "prior 8 bars rose > 1 ATR":     prior8 > 1.0,
        # --- volatility state -------------------------------------------------------
        "BB squeeze (width pct < 0.25)": bb_w_pct < 0.25,
        "quiet (ATR pct < 0.40)":        atr_pct < 0.40,
        "loud (ATR pct > 0.60)":         atr_pct > 0.60,
        # --- participation ----------------------------------------------------------
        "volume > 1.2x SMA20":    vr > 1.2,
        "volume > 1.5x SMA20":    vr > 1.5,
        # --- trend ------------------------------------------------------------------
        "close > EMA50":          c > e50,
        "close < EMA50":          c < e50,
        "close > EMA200":         c > e200,
        "close < EMA200":         c < e200,
        "EMA20 > EMA50":          e20 > e50,
        "EMA20 < EMA50":          e20 < e50,
        "ADX(14) < 20 (range)":   adx14 < 20,
        "ADX(14) > 25 (trend)":   adx14 > 25,
        "+DI > -DI":              pdi > mdi,
        "-DI > +DI":              mdi > pdi,
        # --- candles ----------------------------------------------------------------
        "bullish engulfing":      bullish_engulfing(o, h, l, c),
        "bearish engulfing":      bearish_engulfing(o, h, l, c),
        "bullish harami":         bullish_harami(o, h, l, c),
        "bearish harami":         bearish_harami(o, h, l, c),
        "hammer":                 hammer(o, h, l, c),
        "shooting star":          shooting_star(o, h, l, c),
    }
    return {k: np.nan_to_num(np.asarray(x, dtype=float), nan=0.0).astype(bool) for k, x in f.items()}


def score(bars, legs, feats, label):
    n = len(bars)
    eligible = np.zeros(n, dtype=bool)
    eligible[WARMUP:n - 40] = True
    up = np.zeros(n, dtype=bool)
    dn = np.zeros(n, dtype=bool)
    for s, e, d in legs:
        if e - s < MIN_RUN_BARS:
            continue
        for off in range(ENTRY_TOLERANCE + 1):
            if s - off >= 0:
                (up if d > 0 else dn)[s - off] = True
    base_up, base_dn = up[eligible].mean(), dn[eligible].mean()
    print(f"\n  base rate: up-run start {base_up:.2%} · down-run start {base_dn:.2%}")
    rows = []
    for name, m in feats.items():
        sel = m & eligible
        k = int(sel.sum())
        if k < 200:
            continue
        pu, pd_ = up[sel].mean(), dn[sel].mean()
        tot = pu + pd_
        rows.append({"condition": name, "n": k,
                     "p_up": float(pu), "p_down": float(pd_),
                     "lift_up": float(pu / base_up) if base_up else None,
                     "lift_down": float(pd_ / base_dn) if base_dn else None,
                     "share_up": float(pu / tot) if tot > 0 else None})
    return rows, float(base_up), float(base_dn)


def main() -> None:
    minutes = load_cached_minutes(DB, EPIC, CACHE)
    bars_all = resample(minutes, TIMEFRAME)
    results = {"epic": EPIC, "tf": TIMEFRAME, "reversal_atr": REVERSAL_ATR,
               "min_run_bars": MIN_RUN_BARS, "slices": {}}

    for sname, (lo_t, hi_t) in SLICES.items():
        bars = bars_all[(bars_all.index >= lo_t) & (bars_all.index <= hi_t)]
        h = ((bars["high_bid"] + bars["high_ask"]) / 2).to_numpy()
        l = ((bars["low_bid"] + bars["low_ask"]) / 2).to_numpy()
        c = ((bars["close_bid"] + bars["close_ask"]) / 2).to_numpy()
        a = atr_ind(h, l, c, 14)
        legs = [(s, e, d) for s, e, d in zigzag_legs(h, l, c, a) if e - s >= MIN_RUN_BARS]
        durs = np.array([e - s for s, e, d in legs])
        disp = np.array([abs(c[e] - c[s]) / a[s] for s, e, d in legs if a[s] > 0])

        print(f"\n{'='*78}\n{EPIC} {TIMEFRAME} — {sname}: {len(bars):,} bars\n{'='*78}")
        print(f"  runs of >= {MIN_RUN_BARS} bars: {len(legs)}  "
              f"({sum(d > 0 for _, _, d in legs)} up / {sum(d < 0 for _, _, d in legs)} down)")
        days = (bars.index[-1] - bars.index[0]).days or 1
        print(f"  {len(legs)/days:.2f} per day · duration median {np.median(durs):.0f} bars "
              f"({np.median(durs)*5:.0f} min) · size median {np.median(disp):.2f} ATR")

        feats = build_features(bars)
        rows, bu, bd = score(bars, legs, feats, sname)
        results["slices"][sname] = {"runs": len(legs), "base_up": bu, "base_down": bd,
                                    "conditions": rows}
        if sname == "discovery":
            print(f"\n  {'condition':<32} {'n':>7} {'P(up)':>7} {'lift':>6} "
                  f"{'P(dn)':>7} {'lift':>6} {'share up':>9}")
            print("  " + "-" * 78)
            for r in sorted(rows, key=lambda x: -max(x["lift_up"], x["lift_down"])):
                print(f"  {r['condition']:<32} {r['n']:>7,} {r['p_up']:>6.2%} "
                      f"{r['lift_up']:>6.2f} {r['p_down']:>6.2%} {r['lift_down']:>6.2f} "
                      f"{r['share_up']:>8.1%}")

    # what replicates?
    print(f"\n{'='*78}\nDoes anything replicate on validation?\n{'='*78}")
    d = {r["condition"]: r for r in results["slices"]["discovery"]["conditions"]}
    v = {r["condition"]: r for r in results["slices"]["validation"]["conditions"]}
    print(f"  {'condition':<32} {'disc lift':>18} {'valid lift':>18} {'share up d/v':>16}")
    for name in d:
        if name not in v:
            continue
        dd, vv = d[name], v[name]
        best_d = max(dd["lift_up"], dd["lift_down"])
        if best_d < 1.15:
            continue
        print(f"  {name:<32} up{dd['lift_up']:>5.2f}/dn{dd['lift_down']:>5.2f} "
              f"   up{vv['lift_up']:>5.2f}/dn{vv['lift_down']:>5.2f}    "
              f"{dd['share_up']:>6.1%}/{vv['share_up']:>6.1%}")

    OUT.write_text(json.dumps(results, indent=2))
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
