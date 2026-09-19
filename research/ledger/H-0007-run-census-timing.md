---
id: H-0007
title: Fast runs are predictable in timing but not in direction
source: owner-comment
instruments: [OIL_CRUDE, OIL_BRENT, US500]
timeframe: 15min
status: "signal present"
created: 2026-09-19
parent: null
trial_count: 12
---

## Where this came from

The owner proposed inverting the search: *"scan the data for good runs, collect them all over a
certain period, analyse from there and see if there are any reproducible patterns."* Every
hypothesis before this one started from a signal and asked whether it predicted anything. This
starts from the **outcome** and asks what preceded it.

`source: owner-comment` and `parent: null` — it does not inherit H-0005's chain, because it is not
a variation on the confluence entry. It is a different question about the instrument.

**The run definition is his, not mine.** The 12 runs he drew by hand
(`research/marks/2026-09-19-owner-oil-runs.json`) measure 2.6–11.6 ATR (median **5.1**) over 4–28
bars (median **9**). That pairing is the specification: **≥5 ATR within 9 bars**.

## A calibration error, recorded so it is not repeated

The first pass used **≥4 ATR within 28 bars** — his size, his *outer* duration. That gives a base
rate of **~50%**: half of all bars are followed by such a move. It is the market's ordinary state,
not an event, and the census's headline "1.86 runs/day" was an artefact of greedy non-overlapping
selection, not rarity. **A 4 ATR move over 7 hours on 15m bars is nothing.**

What makes his marks distinctive is **speed** — 5.1 ATR in 9 bars is 0.57 ATR/bar, four times
faster. Base rates on OIL_CRUDE discovery:

| within → | 60 min | 135 min | 180 min | 420 min |
|---|---|---|---|---|
| ≥4 ATR | 2.7% | 13.1% | 19.5% | 47.1% |
| **≥5 ATR** | 1.2% | **6.8%** | 11.8% | 35.7% |
| ≥6 ATR | 0.6% | 3.6% | 6.9% | 27.0% |

Any result quoted against a duration without its size, or the reverse, is meaningless.

## Exact rule tested

A **run start** is a bar from which price first reaches ≥5 ATR displacement within 9 bars
(ATR(14) at that bar). Overlapping. "A run starts soon" means within 2 bars.

Candidate filter, its three conditions taken from the discovery census and thresholded by eye:
**ATR percentile < 0.40** (quiet) **AND volume > 1.2× SMA(20) AND hour ∈ {00, 01, 07, 08, 09} UTC.**

Slices: discovery 2024-01-01 → 2025-09-30 (where the filter was chosen), validation
2025-10-01 → 2026-07-31 (looked at once, now burned for this rule), holdout after 2026-07-31
**untouched**.

## Result — timing replicates

| instrument | slice | base rate | filter hit rate | lift |
|---|---|---|---|---|
| OIL_CRUDE | discovery | 10.40% | 17.24% | **×1.66** |
| OIL_CRUDE | validation | 8.60% | 16.30% | **×1.90** |
| OIL_BRENT | discovery | 9.89% | 17.29% | ×1.75 |
| OIL_BRENT | validation | 8.79% | 16.78% | ×1.91 |
| US500 | discovery | 11.31% | 9.38% | ×0.83 |
| US500 | validation | 9.20% | 7.65% | ×0.83 |

On both oils the filter roughly **doubles** the chance of a fast 5-ATR move, and it holds from
discovery into validation rather than decaying. Volume is the strongest single condition (×1.53 /
×1.37); quiet volatility next; the session hours are the weakest but additive.

**It does not transfer to US500** — the session condition inverts (×0.47), which is as expected
since 00–09 UTC is not the US cash session. This is the per-instrument "personality" the project
already assumes, showing up as a measurement rather than an assertion.

## Result — direction does not replicate, at all

Of the filtered bars that did produce a run:

| instrument | discovery | validation |
|---|---|---|
| OIL_CRUDE | 47.9% LONG | 50.8% LONG |
| OIL_BRENT | 48.5% LONG | 52.6% LONG |

**A coin flip, in every oil cell.** The filter says *when*, never *which way*.

(US500 shows 34.4% / 28.8% LONG — a real skew, but on a filter whose timing lift is below 1.0 there,
so it is a different phenomenon and not a result of this rule. Noted, not claimed.)

## Why this matters more than the lift

It is the **second independent route to the same wall**. [[H-0006-confluence-entry-direction]]
found the median OIL_CRUDE entry has favourable excursion +1.67 ATR against adverse +2.08 ATR —
you must survive more than you stand to make. That is what not knowing direction costs, measured
from the trade side. This measures it from the signal side: the move is findable, the side is not.

So the scarce thing in this project has never been the move. Fast 5-ATR runs happen ~6 times a day
on OIL_CRUDE and a simple three-condition filter doubles the odds of catching one. **Direction is
the whole problem.**

## What this does not establish

- **Thresholds were eyeballed** from discovery medians (0.40, 1.2×, those five hours). Not fitted
  numerically, but not free either.
- **Validation is now burned for this rule.** Any refinement goes to the holdout or new data, once.
- **No costs, no exits, no placebo.** This is a conditional probability, not a strategy. A 16% hit
  rate means 84% of filtered bars do not produce a run.
- **Timing alone is not tradeable.** A signal that says "something is about to move" with a 50/50
  side is worth nothing to a directional CFD position on its own.

## Next

The one strategy shape this evidence actually supports is a **breakout**: the filter is the setup,
and the market declares the direction. Enter on the break of a range formed during the quiet
period, accept losing the first part of the move as the price of knowing which way. That fits
"timing yes, direction no" exactly, and nothing else tested here does.

It needs a new hypothesis id, its own pre-registration, and it may not reuse the validation slice.
It should be specified against `docs/instrument-personality-playbook.md` per instrument, since the
session component already fails to transfer to US500.
