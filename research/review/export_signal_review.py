"""Build the review payload for H-0009's mean-reversion signal on OIL_BRENT 5m.

The signal: LONG when RSI(7) < 30 and close <= lower Bollinger(20,2); SHORT on the mirror.
Each record carries a candle window, the entry, the 24-bar horizon the edge was measured at, the
maximum favourable and adverse excursion inside it, and the spread -- because the finding is that
the move does not escape the spread, and that has to be visible rather than asserted.

A random sample (fixed seed) is exported rather than a hand-picked set, so the page shows the
signal as it actually is, losers included.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../review/export_signal_review.py
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.indicators import atr as atr_ind, bollinger, rsi

SPREAD = {"OIL_CRUDE": 0.0351, "OIL_BRENT": 0.0412, "US500": 0.5600}
PAD_BEFORE, HORIZON, PAD_AFTER = 30, 24, 16
SEED = 20260919


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epic", default="OIL_BRENT")
    ap.add_argument("--tf", default="5min")
    ap.add_argument("--db", type=Path, default=Path("../../data/axe-trader.sqlite"))
    ap.add_argument("--sample", type=int, default=400)
    ap.add_argument("--out", type=Path, default=Path("../review/signal-review-data.js"))
    a = ap.parse_args()

    engine_dir = Path(__file__).resolve().parents[1] / "engine"
    bars_all = resample(load_cached_minutes(a.db, a.epic, engine_dir / ".cache"), a.tf)

    slices = {"discovery": ("2024-01-01", "2025-09-30"),
              "validation": ("2025-10-01", "2026-07-31")}
    spread = SPREAD[a.epic]
    out_records, stats = [], {}

    for sname, (lo, hi) in slices.items():
        b = bars_all[(bars_all.index >= pd.Timestamp(lo, tz="UTC")) &
                     (bars_all.index <= pd.Timestamp(hi, tz="UTC"))]
        o = ((b["open_bid"] + b["open_ask"]) / 2).to_numpy()
        h = ((b["high_bid"] + b["high_ask"]) / 2).to_numpy()
        l = ((b["low_bid"] + b["low_ask"]) / 2).to_numpy()
        c = ((b["close_bid"] + b["close_ask"]) / 2).to_numpy()
        atr = atr_ind(h, l, c, 14)
        safe = np.where(atr > 0, atr, np.nan)
        r7 = rsi(c, 7)
        bb_m, bb_u, bb_l = bollinger(c, 20, 2.0)
        n = len(c)
        elig = np.zeros(n, dtype=bool)
        elig[max(600, PAD_BEFORE):n - HORIZON - PAD_AFTER - 1] = True
        longs = np.flatnonzero((r7 < 30) & (c <= bb_l) & elig)
        shorts = np.flatnonzero((r7 > 70) & (c >= bb_u) & elig)

        fwd_l = (c[longs + HORIZON] - c[longs]) / safe[longs]
        fwd_s = -(c[shorts + HORIZON] - c[shorts]) / safe[shorts]
        allfwd = np.concatenate([fwd_l, fwd_s])
        stats[sname] = {
            "signals": int(len(longs) + len(shorts)), "long": int(len(longs)),
            "short": int(len(shorts)),
            "edge_atr": float(np.nanmean(allfwd)),
            "median_atr": float(np.nanmedian(allfwd)),
            "win_rate": float(np.nanmean(allfwd > 0)),
            "cost_atr": float(spread / np.nanmedian(atr)),
            "median_atr_pts": float(np.nanmedian(atr)),
        }

        idx = np.concatenate([longs, shorts])
        side = np.concatenate([np.ones(len(longs), int), -np.ones(len(shorts), int)])
        rng = np.random.default_rng(SEED)
        take = rng.choice(len(idx), size=min(a.sample // 2, len(idx)), replace=False)

        for t in take:
            i, d = int(idx[t]), int(side[t])
            start, stop = i - PAD_BEFORE, min(n, i + HORIZON + PAD_AFTER + 1)
            win = slice(start, stop)
            seg_h, seg_l = h[i + 1:i + HORIZON + 1], l[i + 1:i + HORIZON + 1]
            mfe = (seg_h.max() - c[i]) if d > 0 else (c[i] - seg_l.min())
            mae = (c[i] - seg_l.min()) if d > 0 else (seg_h.max() - c[i])
            out_records.append({
                "slice": sname, "d": "LONG" if d > 0 else "SHORT",
                "e": b.index[i].isoformat().replace("+00:00", "Z"),
                "ep": round(float(c[i]), 4),
                "atr": round(float(atr[i]), 5),
                "rsi": round(float(r7[i]), 1),
                "bbl": round(float(bb_l[i]), 4), "bbu": round(float(bb_u[i]), 4),
                "bbm": round(float(bb_m[i]), 4),
                "spread": round(spread, 4),
                "hz": round(float(d * (c[i + HORIZON] - c[i]) / atr[i]), 4),
                "mfe": round(float(mfe / atr[i]), 4), "mae": round(float(mae / atr[i]), 4),
                "eb": i - start, "hb": i - start + HORIZON,
                "hr": int(b.index[i].hour),
                "b": [[int(ts.timestamp()), round(float(oo), 4), round(float(hh), 4),
                       round(float(ll), 4), round(float(cc), 4)]
                      for ts, oo, hh, ll, cc in zip(b.index[win], o[win], h[win], l[win], c[win])],
            })

    out_records.sort(key=lambda r: r["e"])
    for k, r in enumerate(out_records):
        r["i"] = k
    payload = {"epic": a.epic, "tf": a.tf, "horizon_bars": HORIZON, "spread_pts": spread,
               "seed": SEED, "stats": stats, "signals": out_records}
    a.out.write_text("window.SIGNAL_DATA=" + json.dumps(payload, separators=(",", ":")) + ";")
    print(f"{len(out_records)} sampled signals -> {a.out} "
          f"({a.out.stat().st_size / 1e6:.2f} MB)")
    for s, v in stats.items():
        print(f"  {s:10s} {v['signals']:6,} signals  edge {v['edge_atr']:+.4f} ATR  "
              f"cost {v['cost_atr']:.3f} ATR  win {v['win_rate']:.1%}")


if __name__ == "__main__":
    main()
