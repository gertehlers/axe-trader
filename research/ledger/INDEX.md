# Hypothesis ledger

Running count of everything tried, so the multiple-testing bar can only ever go up. Trial counts
are cumulative down a parent/child chain (spec §6.2.3) and feed the Bonferroni adjustment at G1.

**Hypotheses: 9 · Variants tried: 392 · Reached G1: 0 · Reached G2: 0**

| id | title | instruments | tf | status | trials | created |
|---|---|---|---|---|---|---|
| [H-0001](H-0001-rsi-bb-mean-reversion.md) | RSI/Bollinger mean reversion with the trend | US500 | 5min | `rejected: no signal` | 1 | 2026-09-18 |
| [H-0002](H-0002-short-horizon-continuation.md) | Short-horizon extremes continue, not revert | US500 | 5min | `uneconomic` | 97 | 2026-09-18 |
| [H-0003](H-0003-continuation-longer-horizon.md) | The continuation effect clears costs at a longer horizon | US500 | 5min | `rejected: no signal` | 289 | 2026-09-19 |
| [H-0004](H-0004-us500-overnight-premium.md) | The US500 overnight premium survives CFD financing | US500 | 1d | `inconclusive` | 4 | 2026-09-19 |
| [H-0005](H-0005-confluence-entry-signal.md) | The 4-pillar confluence entry predicts forward returns | US500, OIL_CRUDE, OIL_BRENT | 15min | `signal present` | 30 | 2026-09-19 |
| [H-0006](H-0006-confluence-entry-direction.md) | The confluence entry has the wrong sign on OIL_CRUDE | OIL_CRUDE | 15min | `inconclusive` | 50 | 2026-09-19 |
| [H-0007](H-0007-run-census-timing.md) | Fast runs are predictable in timing but not direction | OIL_CRUDE, OIL_BRENT, US500 | 15min | `signal present` | 12 | 2026-09-19 |
| [H-0008](H-0008-compression-breakout.md) | A volatility-compression breakout converts H-0007's timing into an edge | OIL_CRUDE, OIL_BRENT, US500 | 15min | `rejected: no signal` | 13 | 2026-09-19 |
| [H-0009](H-0009-rsi-bb-oil-mean-reversion.md) | RSI+Bollinger mean reversion is real on oil and smaller than the spread | OIL_BRENT, OIL_CRUDE, US500 | 5min | `uneconomic` | 24 | 2026-09-19 |

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
- **H-0004** is the first hypothesis from a source that had never been mined (`known-effect`,
  spec §6.2.1), so its trial count starts at 4 rather than inheriting 289. The overnight premium is
  **present and large** on US500 — +3.173 pts per session overnight against +0.974 intraday, 77% of
  the daily return accruing while the cash market is shut — and broker financing takes **46%** of
  it. What is left (+1.168 net) cannot be resolved: it needs ~7,689 sessions at 80% power and we
  have 664. Parked at layer 0, and the parked condition is 31 years of data, not a better idea.
  Its one solid result is a prohibition: **holding US500 short overnight loses reliably** (−3.774
  net, CI entirely below zero, 2/11 quarters positive).
- **H-0006** is the first hypothesis raised by the **owner's own eye** (`source: owner-comment`):
  he marked five trades on the review page and three had the entry pointing the wrong way. The
  pre-registered placebo **failed** — percentile 6.2 against a required 5.0, a near miss that is
  recorded as a miss, and the fade line is closed rather than re-run. Its lasting value is the
  diagnostics: the entry is **not late**, it fires *against* a completed ~1.1 ATR move on both
  sides by design; the chop is **the instrument, not the signal** (forward efficiency 0.21–0.24
  against a 0.230 baseline for any bar); and the excursion profile is **upside down** — median MFE
  +1.67 ATR against median MAE +2.08 ATR on the LONG side. No exit rule fixes MAE > MFE, which
  makes the 51-config exit matrix the wrong next step and turns the live question into whether any
  entry on this instrument has MFE > MAE.

- **H-0007** inverts the search at the owner's suggestion: find the good runs first, then look back
  at what preceded them. Run definition taken from the 12 runs he drew by hand (≥5 ATR within 9
  bars; his median is 5.1 ATR in 9). A first pass at ≥4 ATR within **28** bars was discarded as a
  **calibration error** — that is a 50% base rate, the market's ordinary state. Quiet volatility +
  above-average volume + the 00–09 UTC session roughly **doubles** the odds of a fast run on both
  oils and **holds from discovery into validation** (×1.66→×1.90 OIL_CRUDE), but **inverts on
  US500**, where those hours are not the session. Direction is a **coin flip in every oil cell**
  (47.9–52.6% LONG). This is the second independent route to the same wall as
  [[H-0006-confluence-entry-direction]]: the move is findable, the side is not. The only shape the
  evidence supports is a breakout, where the market declares direction.

- **H-0008** was H-0007's one supported follow-up, pre-registered before the run and **rejected**.
  The breakout fails three of four criteria on OIL_CRUDE validation: −0.0851R expectancy, a CI that
  crosses zero, and a placebo percentile of **74.5** against a required 95. Negative on every slice
  and every instrument, and the placebo loses too (−0.11 to −0.21R), so **breakouts on 15m oil are
  negative-expectancy with or without the compression filter**. Not a cost problem: at zero
  slippage validation is still −0.0357R. Two implementation bugs were found and fixed mid-run (a
  stop tested against the entry bar's own low; minutes mapped to bars by elapsed-time arithmetic
  across a series with weekend gaps) — the pre-fix figures are void and the fixes are written up in
  the entry. **Closed, with no variant proposed**: the result is not "nearly worked".

- **H-0009** came from the owner asking to go back to OIL_BRENT 5m and look at what the TA did
  around every 6+ bar run. It **corrects the reading** that direction is unavailable: it is
  unavailable to *continuation* designs, which is every momentum hypothesis in this ledger. As
  **mean reversion** it is real — `RSI(7)<30 AND close<=lower BB` gives +0.097 / +0.305 ATR at 24
  bars against a base rate of +0.008 / +0.081, positive at every horizon in both slices. It is also
  **always inside the spread**: 0.097 against a 0.484 ATR round trip on discovery. Scoring the TA
  battery against ZigZag pivots looked spectacular (up-lift ×4.7, 94% of runs up) and was nearly
  tautological — a pivot *is* where RSI is low — which is recorded as the trap it was. Across 24
  instrument×timeframe×slice cells **not one clears its spread in both slices**, and every cell that
  clears in one flips sign in the other on 100–500 observations. Pillar 1 of the old confluence was
  the right signal on the wrong ground.

- **H-0005** is the first result in this project with an economically meaningful edge.
  **OIL_CRUDE SHORT**: 222 entries, positive at all 5 horizons, percentile 100.0 at 4h, edge
  **+0.1385 pts against a 0.0351 spread — 3.9 spreads**, where H-0002 managed 1.01 and H-0003
  ceilinged at 1.55. OIL_BRENT SHORT shows the same shape more weakly (4/5, best 86.5) and both
  oil LONG sides are clearly negative. Two caveats that must travel with it: Brent and WTI are
  not independent evidence, and while the result passes the pre-registered criteria it does not
  clearly clear a Bonferroni bar at 30 trials. It is a lead to test, not a finding.
  Its first run, at 4h, was `inconclusive` — the confluence fires on ~0.6% of bars and 4h has too
  few bars to produce a testable sample. Both runs are recorded.
- No hypothesis has touched the 2026-08-01 → holdout. It remains unscored.

## Threshold changes

None. No threshold has been moved, before or after any run.

One **status mapping** was corrected, which is not a threshold change and is recorded here so the
distinction stays visible: H-0004's pre-registered criterion 1 fused a layer-2 question (does the
move cover costs?) with a layer-0 question (can this sample resolve it?) into one pass/fail, and
mapped its failure to `uneconomic`. The data separates them the other way — layer 2 passes, layer 0
fails — so the status is `inconclusive` per spec §6.2.3's first-failing-layer rule. The bar was not
moved; the label attached to it was wrong. See H-0004 for the full note.
