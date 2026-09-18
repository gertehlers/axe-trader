---
id: H-0002
title: Short-horizon extremes predict continuation, not reversion
source: exploration
instruments: [US500]
timeframe: 5min
status: uneconomic
created: 2026-09-18
parent: H-0001
trial_count: 97
---

## The idea, in plain language

[[H-0001-rsi-bb-mean-reversion]] failed in a specific and informative way: after RSI(7) < 25 at the
lower band, price kept *falling*. The mirror rule (RSI > 75 at the upper band) showed price kept
*rising*. Both say the same thing — over the next hour a short-term extreme on US500 continues
rather than reverts. Trade with the extreme instead of against it.

## Exact rule (a family, tested as a grid)

    LONG  when RSI(p) > 50 + e/2   [and optionally close >= upper Bollinger(20, 2.0)]
    SHORT when RSI(p) < 50 − e/2   [and optionally close <= lower Bollinger(20, 2.0)]
    with an optional EMA(200) gate: below / none / above

Grid: `p ∈ {7, 14}` × `e ∈ {60, 65, 70, 75}` × band `∈ {off, on}` × trend `∈ {−1, 0, +1}` × side
`∈ {LONG, SHORT}` = **96 configurations**, the trial count recorded above (96 grid cells + 1 parent).

## Pass/fail criteria, set before the run

Spec §6.2.2: development data only; a configuration counts only if ≥70% of its immediate grid
neighbours are also positive (plateau, not peak); and the best real configuration must beat the
95th percentile of the best-placebo distribution over the same grid.

## Runs

### 2026-09-18 — layer 1 grid, development data

- Run: `research/experiments/2026-09-18-us500-momentum-grid.py`, results in the sibling `.json`
- Data: US500 5min, 2024-01-01 → 2026-07-31. Holdout untouched.
- Horizon 60m; 200 clustering-preserving placebo datasets (whole-week shifts of every entry set)

| test | result | verdict |
|---|---|---|
| configurations tested | 64 of 96 (32 under 200 fires → `inconclusive`) | |
| positive edge | 47 of 64 | |
| on a plateau (≥70% neighbours positive) | 40 of 47 | **broad, not a peak** |
| best real vs best-placebo 95th pct | +3.154 vs **+3.582** | **FAILS rule 3** |
| family mean, all 64 configs | +0.798 vs placebo +0.031, pct 96.0 | marginal |
| family mean, 48 configs with ≥500 fires | +0.565 vs placebo +0.061, pct **90.0** | **below the bar** |

The direction is consistent — the tilt is real enough to see across the whole grid — but it does
not clear the bar. The best configurations are also the smallest (n = 207–275), which is what
overfitting looks like; restricting to configurations with enough trades to mean anything drops the
family test from percentile 96.0 to 90.0.

### The economics kill it regardless

Mean spread on US500 over the same window is **0.561 pts**. The family edge among adequately-sized
configurations is **+0.565 pts** per entry at a 60-minute horizon.

The entire edge is one spread wide — before financing, before slippage. Even granting the tilt at
face value and ignoring that it fails rule 3, there is nothing left to collect.

**Result: `uneconomic`.** Allowed follow-up per spec §6.2.3 layer 2: a child hypothesis on a longer
timeframe or a lower-cost instrument, where the same tilt would have to clear a proportionally
smaller cost. Not attempted yet.

## What would change this

- A longer holding horizon, where the tilt has room to exceed a fixed spread. The 120m column is
  recorded per configuration in the JSON and was not analysed.
- A lower-spread instrument. US500's 0.561-pt spread against a ~0.5-pt hourly tilt is the binding
  constraint, and it is instrument-specific.
- Sharper conditioning that raises the per-entry edge rather than the trade count. Every
  configuration here is a blunt RSI threshold.
