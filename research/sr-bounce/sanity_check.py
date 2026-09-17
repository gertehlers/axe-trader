"""Synthetic checks: a random walk bounces ~50%; a walk reflected at a level bounces ~100%."""
import numpy as np

import bounce_test as bt

rng = np.random.default_rng(1)
n = 200_000
t = np.arange(n, dtype=np.int64)
steps = rng.normal(0, 1, n)


def outcomes(close, levels):
    h = close + np.abs(rng.normal(0, 0.3, n))
    l = close - np.abs(rng.normal(0, 0.3, n))
    a = np.full(n, 5.0)
    out = {"bounce": 0, "break": 0, "ambiguous": 0, "unresolved": 0}
    for lv in levels:
        for _, _, o in bt.run_level(lv, 0, n, t, h, l, close, a, 1.0):
            out[o] += 1
    return out


def rate(o):
    return o["bounce"] / (o["bounce"] + o["break"])


free = 1000 + np.cumsum(steps)
walk = outcomes(free, np.arange(free.min(), free.max(), 7.3))
folded = np.abs(np.mod(np.cumsum(steps), 80.0) - 40.0)
reflected = outcomes(1000 + folded, [1000.0, 1040.0])
print("random walk:", walk, f"bounce rate {rate(walk):.1%}")
print("reflected at level:", reflected, f"bounce rate {rate(reflected):.1%}")
assert 0.45 < rate(walk) < 0.55, "random walk should be ~50%"
assert rate(reflected) > 0.9, "reflected walk should bounce"

minutes = np.arange(60, dtype=np.int64)
bars = bt.five_minute_bars(minutes, minutes * 1.0, minutes * 1.0, minutes * 1.0, minutes * 1.0)
completed = np.searchsorted(bars[0] + 5, minutes, side="right") - 1
assert completed[4] == -1 and completed[5] == 0 and completed[9] == 0 and completed[10] == 1, completed[:12]
print("ATR causality: minute bars only see completed 5m buckets — ok")
