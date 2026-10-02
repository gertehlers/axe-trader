"""Brent 5m personality: at what turn size do Brent's swings keep going? Description only, no strategy.

The owner (2026-10-02): the 2 ATR yardstick was arbitrary; what is Brent's own scale?

Zigzag on the 5m bid close: a swing ends when price comes back h against it (h in ATR14 units at
that candle, or in dollars). For each h, every finished swing is measured from turn to turn.

Benchmark, "coin-flip Brent": the same 5m close-to-close moves, in the same order (so busy and quiet
hours stay), with each move's direction flipped by a fair coin. Same volatility, no memory. For a
memoryless market the average swing is about 2 h and about 37% of swings reach 2 h.
  real > coin  -> at that turn size, Brent's turns keep going (trend-like scale)
  real < coin  -> at that turn size, Brent's turns fade (back-and-forth scale)

Practice period only: data from RESERVED_FROM (2026-02-01) is never loaded.

    cd research/engine && PYTHONPATH=. .venv/bin/python ../experiments/2026-10-02-brent-swing-personality.py
"""
from pathlib import Path

import numpy as np
import pandas as pd

from engine.bars import resample
from engine.cache import load_cached_minutes
from engine.dayrun import RESERVED_FROM
from engine.indicators import atr

R = Path(__file__).resolve().parents[2]
m = load_cached_minutes(R / "data/axe-trader.sqlite", "OIL_BRENT", R / "research/engine/.cache")
b = resample(m[m.index < RESERVED_FROM], "5min")
b = b[b.index >= pd.Timestamp("2024-01-11", tz="UTC")]
close = b.close_bid.to_numpy()
a14 = atr(b.high_bid.to_numpy(), b.low_bid.to_numpy(), close, 14)
ok = ~np.isnan(a14)
close, a14, years = close[ok], a14[ok], b.index[ok].year.to_numpy()
days = len(np.unique(b.index[ok].normalize()))


def zigzag(price, threshold):
    """Finished swings as (size in price, size in threshold units, candles, start index)."""
    swings = []
    turn_i, ext_i, up = 0, 0, None
    for i in range(1, len(price)):
        if up is None:
            if abs(price[i] - price[0]) >= threshold[i]:
                up, ext_i = price[i] > price[0], i
            continue
        if (up and price[i] > price[ext_i]) or (not up and price[i] < price[ext_i]):
            ext_i = i
        elif abs(price[ext_i] - price[i]) >= threshold[i]:
            size = abs(price[ext_i] - price[turn_i])
            swings.append((size, size / threshold[ext_i], ext_i - turn_i, turn_i))
            turn_i, ext_i, up = ext_i, i, not up
    return np.array(swings)


def coin_flip(price, seed):
    moves = np.diff(price)
    signs = np.random.default_rng(seed).choice([-1.0, 1.0], len(moves))
    return np.concatenate([[price[0]], price[0] + np.cumsum(moves * signs)])


def stats(sw):
    return {"per_day": len(sw) / days, "avg_h": sw[:, 1].mean(), "reach2": (sw[:, 1] >= 2).mean() * 100,
            "reach3": (sw[:, 1] >= 3).mean() * 100, "usd": np.median(sw[:, 0]), "candles": np.median(sw[:, 2])}


print(f"5m ATR14 on Brent (practice): median ${np.median(a14):.3f}, middle half ${np.percentile(a14, 25):.3f}"
      f"-${np.percentile(a14, 75):.3f}, by year " +
      ", ".join(f"{y}: ${np.median(a14[years == y]):.3f}" for y in np.unique(years)))
print(f"{days} trading days\n")
fakes = [coin_flip(close, seed) for seed in range(5)]


def table(title, unit_of):
    print(title)
    print(f"{'turn h':>8} | {'swings/day':>10} {'median $':>9} {'candles':>8} | {'avg swing/h':>11} {'reach 2h%':>9}"
          f" {'reach 3h%':>9} | {'coin: avg/h':>11} {'2h%':>5} {'3h%':>5} | {'2024 avg/h':>10} {'2025 avg/h':>10}")
    for label, h in unit_of:
        real = zigzag(close, h)
        s = stats(real)
        coin = [stats(zigzag(f, h)) for f in fakes]
        c = {k: np.mean([x[k] for x in coin]) for k in coin[0]}
        start_year = years[real[:, 3].astype(int)]
        by_year = [real[start_year == y, 1].mean() for y in (2024, 2025)]
        print(f"{label:>8} | {s['per_day']:>10.1f} {s['usd']:>9.3f} {s['candles']:>8.0f} | {s['avg_h']:>11.2f}"
              f" {s['reach2']:>9.0f} {s['reach3']:>9.0f} | {c['avg_h']:>11.2f} {c['reach2']:>5.0f} {c['reach3']:>5.0f}"
              f" | {by_year[0]:>10.2f} {by_year[1]:>10.2f}")
    print()


table("Turn size in ATR (h x ATR14 at each candle)",
      [(f"{k:g} ATR", k * a14) for k in (1, 1.5, 2, 3, 4, 5, 6, 8, 10, 12)])
table("Turn size in dollars", [(f"${d:.2f}", np.full(len(close), d)) for d in (0.10, 0.20, 0.30, 0.50, 0.75, 1.00, 1.50, 2.00)])
