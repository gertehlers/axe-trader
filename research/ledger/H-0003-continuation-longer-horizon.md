---
id: H-0003
title: The continuation effect clears costs at a longer horizon
source: exploration
instruments: [US500]
timeframe: 5min
status: "rejected: no signal"
created: 2026-09-19
parent: H-0002
trial_count: 289
---

## The idea, in plain language

[[H-0002-short-horizon-continuation]] found a real direction and no money in it. Over 60 minutes
the family of adequately-sized configurations earned **+0.565 pts** against a **0.561 pt** spread —
the whole edge was one spread wide, so it was closed as `uneconomic`. Spec §6.2.3 layer 2 permits
exactly one follow-up from that verdict: the same effect on a **longer timeframe** or a
lower-cost instrument, where a proportionally larger move faces the same fixed crossing cost.

The reason to think a longer horizon might help is arithmetic, not hope. Spread is paid once per
trade regardless of how long the trade is held. If the continuation is a drift rather than a
one-bar jump, the move grows with holding time while the cost does not, so the edge-to-cost ratio
improves with horizon even though the *signal* is no stronger.

The raw 120-minute column was already computed during H-0002's run and never analysed. Read
directly from `research/experiments/2026-09-18-us500-momentum-grid.json`:

| subset | mean @ 60m | mean @ 120m | ratio | positive @ 60m | positive @ 120m |
|---|---|---|---|---|---|
| all tested (64) | +0.798 | +1.180 | 1.48 | 50 | 61 |
| fires ≥ 500 (48) | +0.565 | +0.848 | 1.50 | 38 | 45 |

That is the motivation and **it is not evidence**: those are raw forward returns with no placebo,
and US500 drifts upward, so a longer horizon inflates any long-side mean for free. The one detail
that argues against pure drift is that SHORT configurations are positive at 120m too (+2.18, +1.59
on the largest ones), and drift works against a short. Only a matched placebo at the same horizon
can decide it, and that is what this hypothesis runs.

## Exact rule

Unchanged from H-0002 — the signal is not being re-tuned, only the holding horizon:

    LONG  when RSI(p) > 50 + e/2   [and optionally close >= upper Bollinger(20, 2.0)]
    SHORT when RSI(p) < 50 − e/2   [and optionally close <= lower Bollinger(20, 2.0)]
    with an optional EMA(200) gate: below / none / above

Grid: `p ∈ {7, 14}` × `e ∈ {60, 65, 70, 75}` × band `∈ {off, on}` × trend `∈ {−1, 0, +1}` × side
`∈ {LONG, SHORT}` = 96 configurations, evaluated at **two horizons, 120m and 240m** = 192 cells.
Cumulative trial count: 97 (parent) + 192 = **289**. The Bonferroni bar at G1 rises accordingly.

Development data only: 2024-01-01 → 2026-07-31. The 2026-08-01 holdout is not touched.

Placebo method: `shifted_placebo_entries` — 200 whole-week shifts of the entire entry set, which
preserves both the clustering of entries and their weekday/hour profile, and destroys only the
alignment with price. The same method H-0002 used.

## Pass/fail criteria, set before the run

This is a layer-2 economics retest, so the economic bar is stated in cost units and set first.
Mean US500 spread over the window is 0.561 pts, paid once per round trip.

**PASS** — promote to a G1 walk-forward candidate — requires **all four**:

1. **Economics.** Family edge (real minus placebo) among configurations with ≥500 fires is
   **≥ 2 × mean spread** at the horizon. Two spreads, not one: an edge equal to its cost collects
   nothing, and at 2× costs still eat half. This is the criterion H-0002 failed at 1.01×.
2. **Significance.** That family mean sits at the **≥95th percentile** of the placebo family
   distribution on the same ≥500-fires subset.
3. **Plateau.** ≥70% of positive configurations have ≥70% of their immediate grid neighbours also
   positive (spec §6.2.2 rule 2).
4. **Best-vs-best.** The best real configuration's mean exceeds the 95th percentile of the
   best-placebo distribution (spec §6.2.2 rule 3) — the rule that stops "best of 192 tries".

**FAIL**, by first criterion that fails:

- Fewer than 200 fires → `inconclusive` (layer 0), never `rejected`.
- Criterion 2 or 4 fails → `rejected: no signal` at this horizon.
- Criterion 2 and 4 pass but criterion 1 fails → `uneconomic`. **This closes the family.** Layer 2
  permits one horizon follow-up and this is it; a third pass at the same signal with a third
  horizon would be knob-turning, and the trial count already says so.
- Criterion 1, 2 and 4 pass but 3 fails → `inconclusive`, recorded as an isolated peak.

## Runs

**Run 1 — 2026-09-19**, `research/experiments/2026-09-19-us500-continuation-horizons.py` + `.json`.
US500 5min, 2024-01-01 → 2026-07-31, 185,893 bars, mean spread 0.561 pts, 200 shifted placebos per
horizon. Holdout untouched.

| criterion | required | 120m | 240m |
|---|---|---|---|
| 1 economics — family edge, fires ≥ 500 | ≥ 2.00 spreads | **1.55** ✗ | **1.54** ✗ |
| 2 significance — family percentile, fires ≥ 500 | ≥ 95.0 | **94.0** ✗ | **80.0** ✗ |
| 3 plateau — share of positives on a plateau | ≥ 0.70 | 60/61 = 0.98 ✓ | 51/57 = 0.89 ✓ |
| 4 best real vs best-placebo 95th | real > placebo | ✗ | ✗ |

**Verdict: `rejected: no signal`**, by the pre-registered ordering — criterion 2 fails first, so it
sets the status ahead of the economics verdict. It is fair to say the significance miss at 120m is
marginal (94.0 against a 95.0 bar), and the bar was written down before the run precisely so that a
94 could not be argued into a 95 afterwards.

## What the run actually settles

The economics answer is cleaner than the significance one and it closes the family on its own.

The premise was that a longer hold earns a larger move against the same one-off spread, so the
edge-to-cost ratio should keep improving with horizon. It does not:

| horizon | family edge (fires ≥ 500) | in spreads |
|---|---|---|
| 60m (parent H-0002) | +0.565 | 1.01 |
| 120m | +0.870 | **1.55** |
| 240m | +0.864 | **1.54** |

The ratio improves once from 60m to 120m and then **flatlines**. That is the signature of a
one-off repricing, not a drift: essentially all of the continuation has happened by two hours and
holding twice as long adds variance without adding edge. So there is no horizon at which this
signal clears its costs — the ceiling is ~1.55 spreads, meaning costs eat about two-thirds of the
gross move, and doubling the hold does not move it.

The same overfitting signature as the parent is present too: the best-edge configurations at 120m
fire 207–279 times, while the ≥500-fire subset sits a full spread lower. Best-of-192 selection, not
a discovery.

Spec §6.2.3 layer 2 permits **one** horizon follow-up from `uneconomic`, and this was it. The
continuation family on US500 is closed. A third pass at the same signal would be knob-turning, and
the cumulative trial count of 289 already prices that in.
