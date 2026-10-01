"""Supertrend flip-to-flip, owner settings, practice period only (2024-01-11 .. 2026-01-31).

Always in the market: long on a flip up, short on a flip down, entering at the next bar open,
crossing the real Bid/Ask spread, paying overnight funding. Data from RESERVED_FROM (2026-02-01)
is never loaded. Output: the .txt next to this file.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-01-supertrend-flip-to-flip.py
"""
from pathlib import Path
import pandas as pd
from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.flips import flip_trades, summarise
from engine.indicators import supertrend
from engine.instruments import load_instruments
R = Path("/Users/gertehlers/Development/projects/axe-trader")
specs = load_instruments(R / "research/engine/instruments.yaml")
START = pd.Timestamp("2024-01-11", tz="UTC")
for epic, tf, n, f in [("US500","5min",7,3),("US500","1h",10,3),("US100","1h",10,3),("OIL_BRENT","5min",7,3)]:
    m = load_cached_minutes(R/"data/axe-trader.sqlite", epic, R/"research/engine/.cache")
    m = m[m.index < RESERVED_FROM]
    b = resample(m, tf)
    _, d = supertrend(b.high_bid, b.low_bid, b.close_bid, n, f)
    t = flip_trades(b, d, specs[epic], start=START)
    s = summarise(t)
    print(f"\n{epic} {tf} {n}/{f}: " + ", ".join(f"{k}={v:.2f}" if isinstance(v,float) else f"{k}={v}" for k,v in s.items()))
    t["year"] = t.exit_time.dt.year
    print(t.groupby(["year","side"]).net_pts.agg(["count","sum","mean"]).round(1).to_string())
    print("avg mfe", round(t.mfe_pts.mean(),1), "median net", round(t.net_pts.median(),2), "first price", round(b.close_bid.iloc[0],1), "last", round(b.close_bid.iloc[-1],1))
