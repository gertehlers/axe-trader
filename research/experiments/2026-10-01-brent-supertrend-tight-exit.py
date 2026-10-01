"""Brent 5m: Supertrend(7, 3) flip as entry, a tighter Supertrend(7, k) as exit. Practice period only.

Exit at the open after the first close that turns the tight line against the trade, else at the
next main flip; flat until the next main flip. Same costs as the flip-to-flip run. Data from
RESERVED_FROM (2026-02-01) is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-01-brent-supertrend-tight-exit.py
"""
from pathlib import Path

import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.flips import flip_trades, summarise
from engine.indicators import supertrend
from engine.instruments import load_instruments

R = Path(__file__).resolve().parents[2]
spec = load_instruments(R / "research/engine/instruments.yaml")["OIL_BRENT"]
m = load_cached_minutes(R / "data/axe-trader.sqlite", "OIL_BRENT", R / "research/engine/.cache")
b = resample(m[m.index < RESERVED_FROM], "5min")
_, main = supertrend(b.high_bid, b.low_bid, b.close_bid, 7, 3.0)
START = pd.Timestamp("2024-01-11", tz="UTC")
print(f"{'exit':<14}{'trades':>7}{'win%':>7}{'net':>9}{'/trade':>8}{'avgwin':>8}{'avgloss':>9}{'pf':>6}"
      f"{'mfe':>7}{'spread':>8}{'gross':>8}{'maxDD':>8}{'bars':>6}")
for k in [None, 2.0, 1.5, 1.0]:
    exit_dir = None if k is None else supertrend(b.high_bid, b.low_bid, b.close_bid, 7, k)[1]
    t = flip_trades(b, main, spec, start=START, exit_direction=exit_dir)
    s = summarise(t)
    gross = t.gross_pts.sum() + t.spread_pts.sum()  # before spread and funding
    print(f"{'flip (baseline)' if k is None else f'tight 7/{k:g}':<14}{s['trades']:>7}{s['win_rate']*100:>7.1f}"
          f"{s['net_pts']:>9.1f}{s['avg_pts']:>8.3f}{s['avg_win_pts']:>8.3f}{s['avg_loss_pts']:>9.3f}"
          f"{s['profit_factor']:>6.2f}{t.mfe_pts.mean():>7.3f}{s['spread_pts']:>8.1f}{gross:>8.1f}"
          f"{s['max_drawdown_pts']:>8.1f}{s['avg_bars']:>6.1f}")
    if k is not None:
        y = t.assign(year=t.exit_time.dt.year).groupby("year").net_pts.sum().round(1).to_dict()
        print(f"{'':<14}by year {y}")
