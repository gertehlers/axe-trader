---
id: H-0001
title: RSI/Bollinger mean reversion in the direction of the long trend
source: owner-comment
instruments: [US500]
timeframe: 5min
status: "rejected: no signal"
created: 2026-09-18
parent: null
trial_count: 1
---

## The idea, in plain language

When US500 dips hard enough to push RSI below 25 and touch the lower Bollinger band, but the
market is still above its long-run average, that dip is a pullback in an uptrend and price should
recover. Buy the dip.

This is the entry that produced the archived Java run's 88.2% win rate, and `CLAUDE.md` calls its
threshold load-bearing: *"rsi-oversold: 25 — entry threshold — load-bearing, don't loosen"*.

## Exact rule

    LONG when   RSI(7) < 25
          AND   close <= lower Bollinger(20, 2.0)
          AND   close > EMA(200)

Indicators on mid prices. For this rule a constant bid/ask offset cancels out of all three
conditions, so mid versus bid is immaterial.

## Pass/fail criteria, set before the run

Layer 1 (spec §6.2.3): mean forward return after the signal must exceed that of random entries at
matched (weekday, hour) times, at the 95th percentile of the placebo distribution. Failing that,
the status is `rejected: no signal` and no follow-up is permitted.

## Runs

### 2026-09-18 — layer 1, development data

- Run: `research/experiments/2026-09-18-us500-layer1.py`, results in the sibling `.json`
- Commit: `5213c21`
- Data: US500 5min, 2024-01-01 → 2026-07-31 (941 days, 185,893 bars). Holdout untouched.
- Signal fired 1,656 times (0.89% of eligible bars, 1.76/day)
- Placebos: 200 matched-time draws, and 200 clustering-preserving whole-week shifts

| horizon | signal | placebo | edge | pct (independent) | pct (clustered) |
|---|---|---|---|---|---|
| 15m | −0.017 | +0.042 | −0.059 | 32.5 | 30.9 |
| 30m | −0.057 | +0.101 | −0.158 | 22.5 | 31.5 |
| 60m | −0.555 | +0.215 | −0.770 | 0.0 | 6.2 |
| 120m | −0.978 | +0.390 | −1.368 | 0.0 | 0.0 |
| 240m | −1.889 | +0.726 | −2.615 | 0.0 | 0.0 |

Excursions agree: from 60m out the signal has **less** MFE than placebo (5.66 vs 6.29) **and more**
MAE (6.63 vs 6.53). Worse in both directions, not merely neutral.

**Result: `rejected: no signal`.** Stronger than absence of signal — the rule is reliably wrong.
Forward returns are negative at every horizon while matched random entries at the same times are
positive. Allowed follow-up per spec: none.

## Note on scope

This tests one rule, not the full 5-pillar confluence. `confluence-threshold: 3` over four enabled
pillars means pillar 1 need not fire at all, so the confluence's entry set is **not** a subset of
this rule's, and a subset of a negative-mean population can have a positive mean regardless. The
confluence as a whole remains untested.

## Why the old run looked good anyway

A 3.0-ATR stop against a 0.75-ATR target wins about 80% of the time on a driftless walk, purely from
geometry. The archived run scored 88.2%. The tight target harvested noise while the wide stop
absorbed the adverse drift this entry was walking into — which is exactly what a high win rate with
near-zero expectancy looks like.

See also [[H-0002-short-horizon-continuation]], the inverted hypothesis this result suggested.
