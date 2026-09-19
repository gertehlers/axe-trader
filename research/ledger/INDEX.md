# Hypothesis ledger

Running count of everything tried, so the multiple-testing bar can only ever go up. Trial counts
are cumulative down a parent/child chain (spec §6.2.3) and feed the Bonferroni adjustment at G1.

**Hypotheses: 3 · Variants tried: 289 · Reached G1: 0 · Reached G2: 0**

| id | title | instruments | tf | status | trials | created |
|---|---|---|---|---|---|---|
| [H-0001](H-0001-rsi-bb-mean-reversion.md) | RSI/Bollinger mean reversion with the trend | US500 | 5min | `rejected: no signal` | 1 | 2026-09-18 |
| [H-0002](H-0002-short-horizon-continuation.md) | Short-horizon extremes continue, not revert | US500 | 5min | `uneconomic` | 97 | 2026-09-18 |
| [H-0003](H-0003-continuation-longer-horizon.md) | The continuation effect clears costs at a longer horizon | US500 | 5min | `rejected: no signal` | 289 | 2026-09-19 |

## Notes

- **H-0001** is the archived Java engine's entry rule. Its rejection is the reason the 88.2% win
  rate never meant anything: a 3.0-ATR stop against a 0.75-ATR target wins ~80% of the time on a
  driftless walk from geometry alone.
- **H-0002** is H-0001 inverted. The direction is right — 40 of 47 positive configurations sit on a
  plateau — but the edge among adequately-sized configurations (+0.565 pts/hour) is almost exactly
  one US500 spread (0.561 pts), so there is nothing to collect. Follow-up is permitted on a longer
  timeframe or a lower-cost instrument.
- **H-0003** is H-0002's one permitted layer-2 follow-up: the same signal held for 120m and 240m
  instead of 60m, on the theory that a longer hold earns a bigger move against the same one-off
  spread. It does not. The edge-to-cost ratio improves once (1.01 → 1.55 spreads from 60m to 120m)
  and then flatlines (1.54 at 240m), which is a one-off repricing rather than a drift. Costs eat
  two-thirds of the move at every horizon tested. **The continuation family on US500 is closed.**
- No hypothesis has touched the 2026-08-01 → holdout. It remains unscored.

## Threshold changes

None yet. Starting values are those in spec §4.2 and §6.2.2.
