---
id: H-0005
title: The 4-pillar confluence entry predicts forward returns
source: prior-lead
instruments: [US500, OIL_CRUDE, OIL_BRENT]
timeframe: 15min
status: "signal present"
created: 2026-09-19
parent: null
trial_count: 30
---

## The idea, in plain language

The archived engine's 4-pillar confluence at threshold 3 has never been tested as a whole.
[[H-0001-rsi-bb-mean-reversion]] tested pillar 1 alone and found it *anti-predictive*, but
3-of-4 means pillar 1 need not fire, so the confluence's entry set is not a subset of H-0001's.

This is a layer-1 test: exit-free, cost-free, forward returns from confluence entry bars against
matched random entries. Spread is layer 2's question and exits are layer 3's.

## Exact rule

Entry bars are those where the confluence score for a side is ≥ 3 of 4 enabled pillars AND the
EMA(200) trend gate permits that side. Pillars and parameters exactly as
`docs/superpowers/specs/2026-09-19-confluence-exit-matrix-design.md` §3.1. The entry is
**unchanged between the two runs below** — only the bar size differs.

Development data only, 2024-01-01 → 2026-07-31. Placebo: 200 whole-week shifts of the entire
entry set, preserving clustering and weekday/hour.

## Pass/fail criteria, set before the run

**SIGNAL PRESENT** requires, for at least one instrument and side:

1. ≥ 200 entry bars (below that the cell is `inconclusive`, layer 0).
2. Mean forward return in the trade's direction is positive at ≥ 3 of the 5 horizons.
3. The percentile against the placebo distribution is ≥ 95 at the best of those horizons.

**FAIL** → `rejected: no signal`. **Fewer than 200 entries everywhere** → `inconclusive`, never
`rejected` — layer 0 precedes layer 1.

**This gate does not stop the build** (spec D4). Its purpose is to fix how the exit matrix is
read.

### A correction to this document's own pre-registration

The first draft recorded `trial_count: 15` in the front matter while the script computed 6. Both
were wrong. Criterion 3 takes the **best percentile across 5 horizons**, which is a maximum over
five comparisons, so the honest count is 3 instruments × 2 sides × 5 horizons = **30**. Corrected
before the result was read, and it raises the multiple-testing bar rather than lowering it.

## Runs

### Run 1 — 2026-09-19, 4h: `inconclusive`

Chosen from the cost ranking, which puts 4h among the cheapest ground. Every cell came back under
the 200-entry floor:

| instrument | LONG entries | SHORT entries |
|---|---|---|
| US500 | 25 | 6 |
| OIL_CRUDE | 13 | 16 |
| OIL_BRENT | 11 | 16 |

**Verdict `inconclusive` — layer 0, not layer 1.** Nothing was tested, so nothing can be rejected.

**Why, and it is structural.** The confluence fires on roughly **0.6% of bars**. That is a property
of the rule, not of the timeframe. The archived config lived on 5-minute bars, where 2.5 years is
~186,000 bars and 0.6% is still ~1,100 entries. At 4h the window holds only ~4,100 bars in total,
so the same rule yields double digits. The cost ranking says trade slower; the entry's rarity says
it needs many bars. Both are correct and they point opposite ways.

The binding constraint is **pillar 1 (RSI+BB), which fires on ~4% of bars**. Requiring 3 of 4 when
one pillar is a 4% event is what makes entries vanish.

**A worry that did not materialise:** pillar 3 (S/R) was expected to be effectively always-on,
because ta4j's `lowest`/`highest` include the current bar so "near support" is trivially true at
every new extreme. It fires 27–46% — elevated, but not degenerate. The quirk matters less than
feared.

### Run 2 — 2026-09-19, 15m: `signal present`

Same entry, smaller bars. Horizons 1, 4, 8, 16, 32 bars (15m, 1h, 2h, 4h, 8h).

| instrument | side | entries | horizons positive | best percentile | verdict |
|---|---|---|---|---|---|
| US500 | LONG | 356 | 3/5 | 86.2 | no signal |
| US500 | SHORT | 133 | — | — | `inconclusive` (under 200) |
| OIL_CRUDE | LONG | 279 | 0/5 | 30.3 | no signal |
| **OIL_CRUDE** | **SHORT** | **222** | **5/5** | **100.0** | **SIGNAL** |
| OIL_BRENT | LONG | 294 | 0/5 | 31.6 | no signal |
| OIL_BRENT | SHORT | 214 | 4/5 | 86.5 | no signal |

**OIL_CRUDE SHORT, by horizon:**

| horizon | mean | placebo | edge | percentile |
|---|---|---|---|---|
| 15m | +0.0000 | −0.0006 | +0.0006 | 50.0 |
| 1h | +0.0167 | +0.0017 | +0.0150 | 72.9 |
| 2h | +0.0791 | +0.0092 | +0.0700 | 97.9 |
| **4h** | **+0.1421** | +0.0036 | **+0.1385** | **100.0** |
| 8h | +0.1239 | +0.0024 | +0.1216 | 89.4 |

**The edge is economically meaningful, which nothing before it was.** +0.1385 pts at 4h against
OIL_CRUDE's 15m spread of **0.0351** is **3.9 spreads**. [[H-0002-short-horizon-continuation]]
managed 1.01 and [[H-0003-continuation-longer-horizon]] ceilinged at 1.55.

## What to believe, and what not to

**The direction is consistent across both oils and both are short.** OIL_CRUDE SHORT passes;
OIL_BRENT SHORT shows the same shape (4/5 positive, rising edge with horizon, best 86.5). Both oil
LONG sides are clearly negative (percentiles 0–31). An effect appearing in two instruments with the
same sign is harder to dismiss than a single cell.

**But Brent and WTI are not independent evidence.** They are two grades of the same commodity and
move together; treating them as two confirmations would be double-counting one observation.

**And the multiple-testing bar is real.** 30 cells were examined. A percentile of 100.0 against 200
placebo sets means p < 1/201 ≈ 0.005; a Bonferroni-adjusted bar at 30 trials is 0.05/30 ≈ 0.0017.
**The result passes the pre-registered criteria and does not clearly clear Bonferroni.** Both
statements are true and neither should be dropped.

**US500's pre-registered short prediction could not be checked.** Spec §3.3 predicted US500 shorts
would underperform on overnight carry. US500 SHORT produced 133 entries — under the floor — so the
check is deferred, not answered.

## Consequence for the exit matrix

The matrix moves to **15m** (owner decision, 2026-09-19), entry unchanged. Read it knowing:

- **OIL_CRUDE SHORT is where signal was found.** That is the cell whose exit arms mean the most.
- **Both oil LONG sides look dead** before costs. Arms will lose there, and that is the entry, not
  the exit.
- **US500 SHORT is underpowered** at threshold 3 and its cells should be read as `inconclusive`.
- 15m is expensive ground — US500 11.7%, oil 21–25% of a typical bar's range per round trip — so a
  positive layer-1 edge can still be eaten. That is layer 2's question and the matrix answers it.
