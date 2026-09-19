---
id: H-0006
title: The confluence entry has the wrong sign on OIL_CRUDE
source: owner-comment
instruments: [OIL_CRUDE]
timeframe: 15min
status: "signal present"
created: 2026-09-19
parent: H-0005
trial_count: 40
---

## Where this came from

The owner marked up five trades on the trade-review page
(https://claude.ai/artifact/GrqdRANKK6AnzCGcgRyXiU, thread
`78cc1bbd-1082-4cc4-90d2-14c2a69a9cba`) and drew thirteen runs by hand. He was not asked about
entry direction — the page was built to ask whether the symmetric 1:1 exit deserved a third of the
matrix budget. What he drew answered a different question.

Three of the five marked entries sat at the **start of a large move in the opposite direction**:

| entry | 4h horizon | what was drawn on the same bars |
|---|---|---|
| SHORT 2024-01-11 05:46 | **−6.892 ATR** | LONG run 00:15→07:15, +0.75 pts (6.4x ATR), "stretched trend" |
| LONG 2024-01-12 13:31 | **−5.960 ATR** | SHORT run 11:30→17:30, −2.53 pts (9.1x ATR), "reversal at level" |
| LONG 2024-01-18 09:01 | **−2.464 ATR** | SHORT run 11:15→13:45, −0.64 pts (4.2x ATR) |

His words, which are the hypothesis: *exit tuning cannot fix a sign error.*

The marks are preserved verbatim in the artifact thread. **They are the lead, not the evidence** —
five trades he chose to look at, all in January 2024, is exactly the selection bias the placebo
grid exists to catch. What follows is the test on all 506 entries.

## Exact rule tested

The entries are the 506 unique (time, side) pairs the four exit arms of
`docs/superpowers/plans/2026-09-19-confluence-exit-matrix.md` took on OIL_CRUDE 15m — the frozen
H-0005 confluence entry, unchanged. Forward mid-price move from the entry bar's close at
1/4/8/16/32 bars (15m, 1h, 2h, 4h, 8h), expressed in ATR(14) units at the entry bar and signed in
the **signalled** direction. "Faded" is the same number negated: taking the opposite side.

Exit-free and cost-free, as a layer-1 measurement. Costs are applied only as the subtraction noted
below, not modelled.

## Result — as signalled

Both sides are negative at the median at **every** horizon, and win under 50% at every horizon.

| side | n | horizon | mean | median | win% |
|---|---|---|---|---|---|
| LONG | 282 | 4h | **−0.3475** | **−0.4618** | 43.6% |
| LONG | 282 | 8h | −0.1857 | −0.4670 | 45.7% |
| SHORT | 224 | 4h | +0.2922 | **−0.3723** | 45.5% |
| SHORT | 224 | 8h | +0.2708 | −0.3661 | 47.8% |

The SHORT side's positive mean against a negative median is the tail that H-0005 measured, and it
is the whole of that result: the typical SHORT entry loses, and fewer than half are profitable at
any horizon. **This corrects the framing published on the review page**, which read H-0005's mean
as "the edge peaks at 4h, so hold longer". It peaks at 4h *in the mean only*.

## Result — faded, split in and out of sample

Split at 60% of entries by time (IS to 2025-07-03, OOS after). 4h horizon.

| sample | side | n | mean | median | win% |
|---|---|---|---|---|---|
| IS | **LONG** | 160 | **+0.2874** | **+0.2882** | 53.1% |
| OOS | **LONG** | 122 | **+0.4263** | **+0.5712** | 59.8% |
| IS | SHORT | 143 | −0.1068 | +0.4786 | 58.0% |
| OOS | SHORT | 81 | **−0.6195** | **−0.0875** | 48.1% |

**Fading the LONG signal holds out of sample and improves**; mean, median and win rate are all
positive in both samples. **Fading the SHORT signal does not** — mean negative in both, and the
OOS median goes negative too. The two sides are not one effect.

Round-trip spread is 0.0351 pts against a median entry ATR of 0.1527 — **0.230 ATR**. Subtracting
it flat: faded LONG is +0.058 (IS) and +0.341 (OOS) at the median, so it survives costs in both,
thinly in-sample.

The owner's five marked trades are all January 2024 and therefore in-sample, so the **OOS column is
independent of what he looked at.**

## What this does not establish

- **It is not a strategy.** "Hold exactly 4h" is a measurement convention, not an exit rule. No
  stop, no slippage, no financing over a 4-hour hold, no weekend gap.
- **The split is ad hoc.** H-0005 used development data to 2026-07-31; this 60/40 split by entry
  count is mine and was chosen once. It is not the project's holdout and must not be reused as one.
- **Multiple testing is now heavy.** 30 cells at H-0005, 10 more here, plus the IS/OOS follow-up.
  A 53.1% in-sample win rate at n=160 is not distinguishable from noise at this bar.
- **No placebo.** H-0005's finding rested on 200 whole-week shifts. Nothing here has been compared
  against matched random entries, so "the LONG signal is anti-predictive" is not yet separable from
  "OIL_CRUDE fell over this window."
- **Fading is not the bearish confluence.** Shorting when the *bullish* 3-of-4 fires is a different
  entry set from the bearish 3-of-4, and only the former was measured.

## Pre-registration — run 2 (written 2026-09-19, before the run)

Fading is negation, so the testable claim is stated on the **as-signalled** measure: the confluence
LONG entry's forward return is *below* what matched random entries achieve.

**Primary test.** OIL_CRUDE, 15m, LONG side, 4h horizon (16 bars), development window
2024-01-01 → 2026-07-31. Placebo: 200 whole-week shifts of the entire LONG entry set (±26 weeks),
preserving clustering and weekday/hour — the H-0005 construction, unchanged.

**Passes only if all three hold:**
1. The real LONG mean sits at **percentile ≤ 5.0** of the placebo distribution (anti-predictive),
   over at least 150 usable placebo sets.
2. The real LONG **median** is negative — so it is not a tail artefact, which is the failure mode
   this hypothesis was raised to catch.
3. The edge against placebo exceeds the round-trip spread of **0.0351 pts**, since a smaller one
   cannot be collected.

**Everything else is secondary and cannot promote this hypothesis:** the other four horizons, the
SHORT side, and the 2026-08-01 → 2026-09-17 holdout (which holds only ~6 weeks and is reported for
direction, not for power). A secondary cell that passes while the primary fails is a failure.

**If it fails**, the LONG anti-prediction is not separable from the window and the fade line is
closed; the sign question returns to `inconclusive` and the exit matrix stands as the open question.

**The owner's other two observations** (2026-09-19), tested alongside as diagnostics — these carry
no pass/fail and cannot promote anything:
- *"Entries keep starting in choppy waters — is it because it missed the spike, or truly shitty
  confluence?"* Measured as the prior-move size in ATR over the 8 bars before entry (is the signal
  **late**?) and the forward efficiency ratio, net displacement over summed absolute bar moves (is
  the forward window **chop**?), both against the placebo entries.
- *"The exits are way too tiny — we need bigger moves."* Measured as maximum favourable excursion
  per entry against what each arm actually captured.

A mechanism worth naming before it is tested, so it cannot be claimed as a prediction afterwards:
if the confluence fires **after** a move has run, then "fading it" is capturing reversion, and the
sign error and the chop are the same finding. The prior-move diagnostic is what would show that.
