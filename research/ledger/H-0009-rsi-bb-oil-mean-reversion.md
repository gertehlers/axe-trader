---
id: H-0009
title: RSI+Bollinger mean reversion is directionally real on oil, and smaller than the spread
source: owner-comment
instruments: [OIL_BRENT, OIL_CRUDE, US500]
timeframe: 5min
status: "uneconomic"
created: 2026-09-19
parent: null
trial_count: 24
---

## Where this came from

The owner asked to go back to **OIL_BRENT 5-minute** bars, find every run of 6+ bars, look at where
a good entry could have been, and check the surrounding TA for a potential confluence indicator.

The ground was the worst available on cost — rank 15 of 15, 45.0% of a typical bar's range per
round trip — and [[ground-selection]] had found no *unconditional* structure anywhere. But a
variance ratio cannot rule out a *conditional* effect, which is exactly what was being asked for,
so it was worth asking.

## What was done

Runs located as legs of an ATR-threshold ZigZag (a pivot confirmed on a 2-ATR retrace), keeping
legs of ≥6 bars: **577 runs on discovery, 233 on validation**, ~0.8/day, median 12 bars (60 min)
and 2.83 ATR. A battery of 31 TA conditions was then scored at each leg's first bar against the
base rate, **split by direction**, because direction is what every prior attempt had failed on.

## The trap that was avoided, and it nearly wasn't

The battery looked spectacular and replicated:

| condition | discovery lift | validation lift | share of runs that were UP |
|---|---|---|---|
| close ≤ lower BB | up ×3.00 | up ×4.70 | 93.7% / 94.2% |
| RSI(7) < 30 | up ×2.56 | up ×3.54 | 95.4% / 92.7% |
| close ≥ upper BB | down ×3.59 | down ×2.83 | 2.9% / 4.7% |

**These numbers are close to tautological.** An up-leg starts at a swing low *by definition*, and at
a swing low RSI is mechanically low, price is mechanically at the lower band, and it is mechanically
near the 20-bar low. Scoring indicators against future-located pivots rediscovers the definition of
a pivot. **Forward returns are the honest measure**, and they say something different and smaller.

## Forward returns — the signal is real

`RSI(7) < 30 AND close ≤ lower BB` → LONG, mean forward move in ATR units:

| slice | 6 bars | 12 | 24 | 48 | base rate (any bar, 24) |
|---|---|---|---|---|---|
| discovery | +0.026 | +0.068 | **+0.097** | +0.084 | +0.008 |
| validation | +0.087 | +0.216 | **+0.305** | +0.368 | +0.081 |

Positive at every horizon, in both slices, ten times the base rate. **The direction is real** — and
it is *mean reversion*, which is consistent with the variance ratios sitting below 1.0 on every
ground measured.

## And it does not pay for itself

| slice | edge @24 bars | round-trip spread | net |
|---|---|---|---|
| discovery | +0.097 ATR | **0.484 ATR** | **−0.387** |
| validation | +0.305 ATR | **0.300 ATR** | +0.005 |

At best it breaks even. This is [[H-0002-short-horizon-continuation]] again in a different costume:
real direction, +0.565 pts/hour against a 0.561-pt spread. **Fourth time this shape has appeared.**

## Does it clear costs on cheaper ground?

The edge is roughly constant in ATR (~0.1–0.3) while cost/ATR falls as bars slow, so slow ground
should win. `research/experiments/2026-09-19-rsi-bb-across-grounds.py`, edge ÷ cost:

| | 5m | 15m | 1h | 4h |
|---|---|---|---|---|
| OIL_CRUDE | 0.16 / 0.71 | 0.62 / 0.37 | 0.98 / **−1.67** | **2.39** / **−2.87** |
| OIL_BRENT | 0.19 / 0.64 | 0.41 / 0.45 | 0.93 / **−1.40** | **1.38** / **−2.16** |
| US500 | −0.18 / 0.51 | 0.20 / **2.11** | 0.28 / **8.52** | −0.23 / — |

*(discovery / validation; the horizon is the best of four per cell, so every figure is optimistic)*

**Not one ground clears 1.0 in both slices.** Every cell that clears in one flips sign in the other
— OIL_CRUDE 4h goes 2.39 → −2.87 on 220 then 108 observations, and US500 1h goes 0.28 → 8.52 on
1,165 then 526. Those are not edges, they are small samples.

The only consistency is on the **fast oil** cells, positive in both slices on both oils (0.16/0.71,
0.19/0.64, 0.62/0.37, 0.41/0.45) — and never above 0.71. **Real, replicating, and always inside the
spread.**

## What this settles

1. **Direction is available after all** — on oil, conditionally, as mean reversion. That is a real
   correction to the reading after [[H-0008-compression-breakout]] that "direction is unavailable".
   It was unavailable to *continuation* designs. Every momentum design in this ledger (H-0002,
   H-0003, H-0007, H-0008) failed, and the variance ratios said why: nothing trends anywhere.
2. **The edge is structurally smaller than the spread at fast timeframes**, and at slow ones the
   sample collapses before it can be measured. The same pincer as [[H-0004]].
3. **The existing 4-pillar confluence had the right pillar and the wrong ground.** Pillar 1 is
   exactly this signal. It was tested on US500 5m ([[H-0001-rsi-bb-mean-reversion]],
   `rejected: no signal`) where this table shows −0.18 on discovery, and later bolted to a trend
   gate and a continuation thesis that the data does not support.

## What would have to be true next

A mean-reversion edge of ~0.1–0.3 ATR needs ground where the round trip costs well under that. On
the instruments held, that means 1h or slower — where 500–1,200 observations per slice cannot
resolve it. **This is the data wall again, and the same answer as `research/ground-selection/`:
the constraint is history, not ideas.**

No variant is proposed and nothing is pre-registered from this. It is exploratory throughout: the
horizon was chosen as the best of four per cell, and 24 cells were examined.
