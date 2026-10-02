"""Brent 5m: Supertrend(7, 1) + RSI(7) entry, exit at the next Supertrend(7, 1) flip. Practice period only.

The owner's idea (2026-10-02): combine ST 7/1 with RSI 7 for entry, exit on flip for the first run.
RSI is Wilder's RSI(7) on the bid close, the same as Capital.com's default RSI.

Entry, at most one trade per Supertrend leg (flip to next flip):
- at flip:  the flip candle's own RSI must agree (long: RSI > hi, short: RSI < lo), else skip the leg.
- in leg:   the first closed candle in the leg whose RSI agrees, flip candle included.
Fill at the next open, exit at the open after the next flip, flat in between legs. Same costs and
funding as the flip-to-flip runs. Data from RESERVED_FROM (2026-02-01) is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-st71-rsi7.py
"""
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.costs import charge_times, financing_usd
from engine.dayrun import RESERVED_FROM
from engine.flips import COLUMNS, flip_trades, summarise
from engine.indicators import rsi, supertrend
from engine.instruments import load_instruments

R = Path(__file__).resolve().parents[2]
spec = load_instruments(R / "research/engine/instruments.yaml")["OIL_BRENT"]
m = load_cached_minutes(R / "data/axe-trader.sqlite", "OIL_BRENT", R / "research/engine/.cache")
b = resample(m[m.index < RESERVED_FROM], "5min")
_, st = supertrend(b.high_bid, b.low_bid, b.close_bid, 7, 1.0)
st = np.asarray(st)
r = rsi(b.close_bid.to_numpy(), 7)
START = pd.Timestamp("2024-01-11", tz="UTC")


def gated_trades(bars, direction, rsi_values, hi, lo, mode):
    idx = bars.index
    flips = [i for i in range(1, len(bars) - 1) if direction[i] != direction[i - 1] and idx[i] >= START]
    ob, oa = bars["open_bid"].to_numpy(), bars["open_ask"].to_numpy()
    hb, la = bars["high_bid"].to_numpy(), bars["low_ask"].to_numpy()
    lb, ha = bars["low_bid"].to_numpy(), bars["high_ask"].to_numpy()
    cut_off = spec["overnight_fee"]["charge_time_utc"]
    rows = []
    for signal, next_signal in zip(flips, flips[1:]):
        long = direction[signal] == 1
        agree = rsi_values[signal:next_signal] > hi if long else rsi_values[signal:next_signal] < lo
        if mode == "at flip":
            if not agree[0]:
                continue
            confirm = signal
        else:
            hits = np.nonzero(agree)[0]
            if not len(hits):
                continue
            confirm = signal + int(hits[0])
        e, x = confirm + 1, next_signal + 1
        side = "LONG" if long else "SHORT"
        entry, exit_ = (oa[e], ob[x]) if long else (ob[e], oa[x])
        gross = exit_ - entry if long else entry - exit_
        if long:
            mfe, mae = hb[e:x].max() - entry, entry - lb[e:x].min()
        else:
            mfe, mae = entry - la[e:x].min(), ha[e:x].max() - entry
        financing = financing_usd(side, 1.0, entry, spec, charge_times(idx[e], idx[x], cut_off), 1.0)
        rows.append([side, idx[confirm], idx[e], entry, idx[x], exit_, x - e, gross, financing,
                     gross + financing, ((oa[e] - ob[e]) + (oa[x] - ob[x])) / 2.0, mfe, mae])
    return pd.DataFrame(rows, columns=COLUMNS)


def line(name, t):
    s = summarise(t)
    gross = t.gross_pts.sum() + t.spread_pts.sum()
    print(f"{name:<22}{s['trades']:>7}{s['win_rate']*100:>7.1f}{s['net_pts']:>9.1f}{s['avg_pts']:>8.3f}"
          f"{s['avg_win_pts']:>8.3f}{s['avg_loss_pts']:>9.3f}{s['profit_factor']:>6.2f}{s['spread_pts']:>8.1f}"
          f"{gross:>8.1f}{s['max_drawdown_pts']:>8.1f}{s['avg_bars']:>6.1f}")
    y = t.assign(year=t.exit_time.dt.year).groupby("year").net_pts.sum().round(1).to_dict()
    sides = t.groupby("side").net_pts.agg(["count", "sum"]).round(1).to_dict("index")
    print(f"{'':<22}by year {y}  by side {sides}")


print(f"{'entry':<22}{'trades':>7}{'win%':>7}{'net':>9}{'/trade':>8}{'avgwin':>8}{'avgloss':>9}{'pf':>6}"
      f"{'spread':>8}{'gross':>8}{'maxDD':>8}{'bars':>6}")
line("ST 7/1 flip (no RSI)", flip_trades(b, st, spec, start=START))
for hi, lo in [(50, 50), (60, 40), (70, 30)]:
    for mode in ["at flip", "in leg"]:
        line(f"RSI {hi}/{lo} {mode}", gated_trades(b, st, r, hi, lo, mode))
