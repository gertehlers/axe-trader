---
id: H-0008
title: A volatility-compression breakout converts H-0007's timing into an edge
source: prior-lead
instruments: [OIL_CRUDE, OIL_BRENT, US500]
timeframe: 15min
status: "rejected: no signal"
created: 2026-09-19
parent: H-0007
trial_count: 13
---

## The idea, in plain language

[[H-0007-run-census-timing]] found a filter that roughly doubles the odds of a fast 5-ATR move on
oil and holds out of discovery — but the direction of that move is a coin flip (47.9% / 50.8%
LONG). [[H-0006-confluence-entry-direction]] found the same gap from the trade side: favourable
excursion +1.67 ATR against adverse +2.08 ATR, which is the cost of not knowing the side.

A breakout is the one structure that spends a timing signal without needing a directional one: the
filter picks the moment, and **the market declares the side**. The price is the part of the move
that happens before the break.

**Full specification:** `docs/superpowers/specs/2026-09-19-compression-breakout-design.md`.
It is fixed and committed before any run. Summarised: setup = H-0007's three conditions unchanged;
range = the 10 bars ending at the setup bar; entry = first touch of either boundary within 3 bars,
skipping any bar that touches both; stop = opposite boundary; time stop = 9 bars; no target;
slippage 10 ticks.

## Pre-registered criteria — written before the run

**Primary: OIL_CRUDE, validation slice 2025-10-01 → 2026-07-31. All four required.**

1. **n ≥ 100** trades.
2. Net expectancy per trade **> 0** after spread, 10-tick slippage and financing.
3. Bootstrap **95% CI entirely above zero** (10,000 resamples, seed 20260919).
4. **Percentile ≥ 95 against placebo** — identical mechanics at hour-matched bars *without* the
   quiet and volume conditions, 200 sets.

Criterion 4 is the one that carries the hypothesis. Oil breaks out sometimes; the question is
whether H-0007's setup adds anything to that.

**Secondary — reported, cannot promote:** OIL_CRUDE holdout (2026-08-01 →, expected underpowered);
OIL_BRENT validation (*not independent* — same underlying as WTI); discovery (contaminated, since
H-0007's filter was chosen there).

**Sensitivity — reported, not decisive:** slippage 0 / 5 / 10 / 20 / 40 ticks. An edge that exists
only below 10 ticks is not an edge and will be called one that isn't.

**On failure:** this specification closes. A different range length, a target, a trailing stop or
other session hours is a **new hypothesis with a new id** — not a re-run. This is the rule the
project restarted to enforce.

## Data budget, stated honestly

Discovery is contaminated for this family: H-0007's thresholds were eyeballed from its census.
Validation has had H-0007's *lift* measured on it, so the setup is known to fire there — but no
breakout economics have ever been computed on any slice, which is why validation is still the
primary. The holdout is ~3,000 bars and is expected to be too small to decide anything.

If this passes, the next step is a forward test in MONITOR mode, **not capital**, because intrabar
stop fills are modelled from 1-minute bars and a stop order in a fast market is not guaranteed.

## Why the trial count starts at 13

Inherits H-0007's 12 and adds this one configuration. It does **not** inherit the H-0005/H-0006
chain: the confluence entry is not used here in any form. That entry is a mean-reversion design
and this is a continuation design; they share only the instrument.

## Result — 2026-09-19: **FAILS**, on three of four criteria

`research/experiments/2026-09-19-compression-breakout.py`. The criteria above were committed before
the run and are unchanged.

**Primary — OIL_CRUDE, validation:**

| criterion | required | observed | |
|---|---|---|---|
| trades | ≥ 100 | 283 | pass |
| net expectancy | > 0 | **−0.0851R** | **fail** |
| bootstrap 95% CI | entirely > 0 | **[−0.1872, +0.0191]** | **fail** |
| placebo percentile | ≥ 95 | **74.5** | **fail** |

Criterion 4 is the one that mattered and it is not close. The setup beats 74.5% of hour-matched
placebo draws — better than a coin flip, nowhere near significance. **H-0007's filter adds nothing
reliable to a plain breakout.**

**Every slice, every instrument, negative:**

| | OIL_CRUDE | OIL_BRENT | US500 |
|---|---|---|---|
| discovery | −0.2104R (pct 3.5) | −0.2323R (pct 1.5) | −0.1237R (pct 2.0) |
| validation | −0.0851R (pct 74.5) | −0.1358R (pct 35.0) | −0.1118R (pct 14.0) |
| holdout | −0.2449R (pct 22.5) | −0.2321R (pct 40.0) | −0.1903R (pct 19.5) |

The placebo percentile has no consistent sign — 3.5, 74.5, 22.5 on OIL_CRUDE — which is what noise
looks like. On discovery the setup did **worse** than its own placebo.

**It is not a cost problem.** At zero slippage the validation expectancy is −0.0357R; costs move it
to −0.0851R at the pre-registered 10 ticks. Removing costs entirely does not make it positive.

**The placebo loses too** (−0.11 to −0.21R). Breakouts on 15m oil are negative-expectancy whether or
not the compression filter is applied. That is the finding, and it is larger than this hypothesis.

## Two implementation bugs, recorded because the first numbers were published to nobody but nearly were

Both were found by disbelieving a result rather than by a test, which is the uncomfortable part.

1. **The stop was checked on the entry bar's own 15-minute low.** A bar that breaks upward out of a
   range has its low back *inside* the range, from price action *before* the fill. It stopped 72% of
   trades out on movement that had already happened, giving a 12.7% win rate and −0.77R. The stop
   scan now starts at the minute after the fill.
2. **Minutes were mapped to bars by dividing elapsed time by the bar width.** That assumes a
   contiguous series. Oil closes overnight and at weekends: the validation slice holds 19,636 bars
   where a gapless span would hold ~29,000, so minutes were attached to the wrong bars entirely.
   Now mapped by `searchsorted` on the real bar index and verified against the raw bid series
   (0 mismatches in 400 bars).

**Any figure from before those fixes is void.** The lesson for the next strategy that needs intrabar
fills: verify the minute-to-bar mapping against the aggregated bars *before* interpreting anything,
and never test a stop against a bar that contains the entry.

## Status

`rejected: no signal`. **This specification is closed.** Per the pre-registration, a different range
length, a target, a trailing stop or other session hours is a **new hypothesis with a new id** — not
a re-run of this one. No variant is proposed here, deliberately: the result is not "nearly worked".
