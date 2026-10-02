"""Brent 5m Supertrend(7, 1): do "clear reversal" signs before a flip pick entries that really reverse?

The owner (2026-10-02): enter only where there are clear signs of a reversal, to avoid sideways and
choppy water. Forget the money for now; judge the entry only: after it, does price really reverse
(even if it first goes against us for a while), without holding overnight?

Signs, known when the flip candle closes (long flip shown, short is the mirror; ATR = ATR14 at the flip):
  1 leg     the down leg being reversed travelled >= X ATR (close at its flip candle to its lowest low)
  2 exhaust RSI7 was <= Y in the last 6 candles up to and including the flip candle
  3 break   the flip candle closes above the highest high of the previous N candles
  4 higher  the down leg's low is above the low of the down leg before it (higher low)

Outcome, per entry (fill at the next open, long buys the Ask, judged on the Bid; short the reverse):
  reached +T ATR in our favour before being S ATR against us, and before the 21:00 UTC cut-off
  (Brent's overnight-fee time). If one candle touches both, it counts as stopped. Entries only
  00:00-19:59 UTC, so every trade has at least an hour. Each flip is judged on its own (no
  one-at-a-time rule), so the numbers rate entries, not a running account.

Thresholds are fixed in advance; 2024 and 2025 are shown side by side to check they hold.
Practice period only: data from RESERVED_FROM (2026-02-01) is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-st71-reversal-signs.py
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
STOPS, TARGETS = [2.0, 3.0, 4.0], [1.0, 2.0, 3.0]

flips = [i for i in range(1, len(b) - 1) if st[i] != st[i - 1]]
rows = []
for k in range(3, len(flips)):
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
    fav, adv = np.maximum.accumulate(fav), np.maximum.accumulate(adv)
    for s in STOPS:
        hit_s = np.nonzero(adv >= s * A)[0]
        s_at = hit_s[0] if len(hit_s) else len(adv)
        row[f"stop{s:g}"] = s_at < len(adv)
        for t in TARGETS:
            hit_t = np.nonzero(fav >= t * A)[0]
            row[f"t{t:g}s{s:g}"] = bool(len(hit_t)) and hit_t[0] < s_at
    rows.append(row)
df = pd.DataFrame(rows)

SIGNS = {"leg": lambda x: x.travel >= 2, "exhaust": lambda x: x.ex25,
         "break": lambda x: x.br12, "higher": lambda x: x.higher}


def report(name, mask):
    cells = []
    for year in (2024, 2025):
        x = df[mask & (df.year == year)]
        if not len(x):
            cells.append(f"{0:>5}" + " " * 30)
            continue
        cells.append(f"{len(x):>5}" + "".join(f"{x[c].mean()*100:>6.0f}" for c in
                     ["t1s3", "t2s3", "t3s3", "t2s2", "t2s4"]))
    print(f"{name:<34}" + "   |".join(cells))


head = f"{'n':>5}{'+1/3':>6}{'+2/3':>6}{'+3/3':>6}{'+2/2':>6}{'+2/4':>6}"
print("% of entries reaching +T ATR before -S ATR and before 21:00 UTC (column '+T/S')")
print(f"{'':<34}{'2024':^35}   |{'2025':^35}")
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
