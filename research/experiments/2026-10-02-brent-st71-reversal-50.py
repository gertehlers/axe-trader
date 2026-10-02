"""Brent 5m Supertrend(7, 1): after a flip entry, did a reversal happen within 50 candles? (diagnostic)

The owner (2026-10-02) corrected the outcome of the reversal-signs run: a real reversal may go against
us on the very next candle and then bounce back. So, no stop: within the next 50 candles (about
4 hours), did price get +T ATR our way at any point? This only checks whether the 7/1 flip exit
leaves too soon; it does not change the strategy.

Same entries and signs as 2026-10-02-brent-st71-reversal-signs.py (entries 00:00-19:59 UTC, window
also ends at the 21:00 UTC overnight-fee time). Extra columns:
  vs2   control: % that went 2 ATR AGAINST us in the same window (if this is as high as '+2',
        price simply moved a lot both ways, which is not a reversal)
  early of the entries that reached +2 ATR, % where the 7/1 flip exit had already closed the trade
  c2    median candles from entry to +2 ATR (entries that reached it)
  dip2  median ATR against us before reaching +2 ATR

Practice period only: data from RESERVED_FROM (2026-02-01) is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-st71-reversal-50.py
"""
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.indicators import atr, rsi, supertrend

R = Path(__file__).resolve().parents[2]
m = load_cached_minutes(R / "data/axe-trader.sqlite", "OIL_BRENT", R / "research/engine/.cache")
b = resample(m[m.index < RESERVED_FROM], "5min")
idx = b.index
hb, lb, cb = b.high_bid.to_numpy(), b.low_bid.to_numpy(), b.close_bid.to_numpy()
ha, la = b.high_ask.to_numpy(), b.low_ask.to_numpy()
ob, oa = b.open_bid.to_numpy(), b.open_ask.to_numpy()
_, st = supertrend(b.high_bid, b.low_bid, b.close_bid, 7, 1.0)
st = np.asarray(st)
a14 = atr(hb, lb, cb, 14)
r7 = rsi(cb, 7)
START = pd.Timestamp("2024-01-11", tz="UTC")
WINDOW, TARGETS = 50, [1.0, 2.0, 3.0]

flips = [i for i in range(1, len(b) - 1) if st[i] != st[i - 1]]
rows = []
for k in range(3, len(flips) - 1):
    i = flips[k]
    e = i + 1
    if idx[i] < START or idx[e].hour >= 20 or np.isnan(a14[i]):
        continue
    deadline = idx[e].normalize() + pd.Timedelta(hours=21)
    d = int(np.searchsorted(idx, deadline)) - 1  # last candle starting before 21:00
    if d < e:
        continue
    long = st[i] == 1
    A = a14[i]
    leg = slice(flips[k - 1], i + 1)          # the leg being reversed, flip candle included
    old_leg = slice(flips[k - 3], flips[k - 2])  # the same-direction leg before it
    if long:
        travel = (cb[flips[k - 1]] - lb[leg].min()) / A
        stretch = np.nanmin(r7[i - 5:i + 1])
        exhaust = {y: stretch <= y for y in (30, 25, 20)}
        brk = {n: cb[i] > hb[i - n:i].max() for n in (6, 12, 24)}
        higher = lb[leg].min() > lb[old_leg].min()
        entry = oa[e]
        fav, adv = hb[e:d + 1] - entry, entry - lb[e:d + 1]
    else:
        travel = (hb[leg].max() - cb[flips[k - 1]]) / A
        stretch = np.nanmax(r7[i - 5:i + 1])
        exhaust = {y: stretch >= 100 - y for y in (30, 25, 20)}
        brk = {n: cb[i] < lb[i - n:i].min() for n in (6, 12, 24)}
        higher = hb[leg].max() < hb[old_leg].max()   # lower high
        entry = ob[e]
        fav, adv = entry - la[e:d + 1], ha[e:d + 1] - entry
    row = {"year": idx[e].year, "side": "LONG" if long else "SHORT", "travel": travel, "higher": higher}
    row.update({f"ex{y}": v for y, v in exhaust.items()})
    row.update({f"br{n}": v for n, v in brk.items()})
    w = min(WINDOW, len(fav))
    row["short_window"] = w < WINDOW
    fav, adv = np.maximum.accumulate(fav[:w]), np.maximum.accumulate(adv[:w])
    exit_at = flips[k + 1] + 1 - e if k + 1 < len(flips) else 10**9  # candles until the 7/1 flip exit
    for t in TARGETS:
        hit = np.nonzero(fav >= t * A)[0]
        row[f"t{t:g}"] = bool(len(hit))
        if t == 2.0:
            row["c2"] = hit[0] + 1 if len(hit) else np.nan
            row["dip2"] = adv[hit[0]] / A if len(hit) else np.nan
            row["early"] = bool(len(hit)) and exit_at <= hit[0]
    row["vs2"] = bool((adv >= 2 * A).any())
    rows.append(row)
df = pd.DataFrame(rows)
print(f"window cut short by 21:00: {df.short_window.mean()*100:.0f}% of entries")

SIGNS = {"leg": lambda x: x.travel >= 2, "exhaust": lambda x: x.ex25,
         "break": lambda x: x.br12, "higher": lambda x: x.higher}


def report(name, mask):
    cells = []
    for year in (2024, 2025):
        x = df[mask & (df.year == year)]
        if not len(x):
            cells.append(f"{0:>5}" + " " * 36)
            continue
        reached = x[x.t2]
        cells.append(f"{len(x):>5}" + "".join(f"{x[c].mean()*100:>6.0f}" for c in ["t1", "t2", "t3", "vs2"])
                     + f"{reached.early.mean()*100 if len(reached) else 0:>6.0f}"
                     + f"{reached.c2.median() if len(reached) else 0:>5.0f}{reached.dip2.median() if len(reached) else 0:>5.1f}")
    print(f"{name:<34}" + "   |".join(cells))


head = f"{'n':>5}{'+1':>6}{'+2':>6}{'+3':>6}{'vs2':>6}{'early':>6}{'c2':>5}{'dip2':>5}"
print("% of entries reaching +T ATR our way within 50 candles (no stop; window ends 21:00 UTC)")
print(f"{'':<34}{'2024':^41}   |{'2025':^41}")
print(f"{'entry rule':<34}{head}   |{head}")
everything = pd.Series(True, index=df.index)
report("all 7/1 flips (baseline)", everything)
print("-- each sign alone, thresholds swept")
for X in (1, 2, 3):
    report(f"1 leg >= {X} ATR", df.travel >= X)
for y in (30, 25, 20):
    report(f"2 exhaust RSI7 <= {y} (6 candles)", df[f"ex{y}"])
for n in (6, 12, 24):
    report(f"3 break {n}-candle high", df[f"br{n}"])
report("4 higher low", df.higher)
print("-- combinations (leg >= 2 ATR, RSI <= 25, 12-candle break, higher low)")
for size in (2, 3, 4):
    for combo in combinations(SIGNS, size):
        mask = everything.copy()
        for sign in combo:
            mask &= SIGNS[sign](df)
        report(" + ".join(combo), mask)
print("-- all four, by side")
mask = everything.copy()
for sign in SIGNS:
    mask &= SIGNS[sign](df)
for side in ("LONG", "SHORT"):
    report(f"all four, {side}", mask & (df.side == side))
