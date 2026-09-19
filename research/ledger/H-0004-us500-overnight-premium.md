---
id: H-0004
title: The US500 overnight premium survives CFD financing
source: known-effect
instruments: [US500]
timeframe: 1d
status: "inconclusive"
created: 2026-09-19
parent: null
trial_count: 4
---

## The idea, in plain language

The most replicated anomaly in equity indices is that essentially the **entire** long-run return
accrues *overnight* — from the cash close to the next cash open — while the intraday session
contributes approximately nothing. See `research/sources/known-effects.md` entry A for citations
(Cliff, Cooper & Gulen 2008; Lachance 2015; Bogousslavsky 2021).

The economic reason is a risk transfer with a named losing side: intraday liquidity providers
systematically flatten into the close rather than carry inventory across a closed book, because
their risk limits and mandates do not permit it. Whoever does carry it is paid for it. That
constraint is structural, so the premium is not an error that gets corrected.

**The question is not whether the premium exists. It is whether we can be paid it.** On a CFD you
pay financing to hold overnight — US500's long rate is −0.0215%/day, which the cost reality check
([[2026-09-19]]) measured at **1.30 pts per night against a 66.8-pt median daily range**. That is
precisely the cost the overnight premium compensates. So this is a head-on test with a number on
both sides, and both answers are worth having:

- If the premium survives financing, it is the cleanest edge available to us, on the second-cheapest
  ground on the board, with the best power in the list.
- If it does not, that single measurement explains why every long-biased idea on this instrument has
  struggled, and it closes a whole family of them rather than one more variant.

Unlike [[H-0001-rsi-bb-mean-reversion]], [[H-0002-short-horizon-continuation]] and
[[H-0003-continuation-longer-horizon]], this hypothesis does not come from the archived Java config.
It is the first from a source that has never been mined, so its trial count starts at 4, not 289.

## Exact rule

Sessions are defined in **America/New_York**, not UTC, because the effect is about the US cash
session and the cash session moves with US daylight saving. `instruments.yaml` records opening hours
in one DST state only and must not be used for this.

    cash open   = first bar at or after 09:30 America/New_York
    cash close  = last bar at or before 16:00 America/New_York

    INTRADAY leg  = mid(cash close, day d)  − mid(cash open, day d)
    OVERNIGHT leg = mid(cash open,  day d)  − mid(cash close, day d−1)

The US500 CFD trades ~23h, so the overnight leg is a *held position*, not an untradeable gap — this
is the whole reason the hypothesis is testable at all on this instrument.

Grid: {overnight, intraday} × {long, short} = **4 cells**, the trial count. The intraday leg and the
short side are controls, not candidates: the documented effect predicts overnight-long positive and
intraday flat, and a result showing both legs positive would indicate drift contamination rather
than the effect.

Costs charged per leg, from the engine's own model so no separate cost assumption can creep in:

- **spread** — mean(close_ask − close_bid), once per round trip.
- **financing** — `engine.costs.charge_times` counted over the actual holding interval, so a
  Friday-close → Monday-open hold is charged for every 21:00 UTC cut-off it spans, not one. This
  deliberately does **not** use the `weekend_multiplier` default of 1.0, which is the open question
  carried from the 2026-09-18 handover and which understates weekend carry.

Development data only: 2024-01-01 → 2026-07-31. The 2026-08-01 holdout is not touched.

## Pass/fail criteria, set before the run

**PASS** — promote to a G1 walk-forward candidate — requires **all four**:

1. **Net expectancy.** The overnight-long leg's mean **net** return (after spread and financing) is
   **> 0**, with the bootstrap 95% CI **lower bound > 0**. Net, not gross: a gross-positive,
   net-negative result is the exact failure mode of H-0002 and is a fail here.
2. **Power.** ≥100 observations. (~660 expected; this should pass trivially and is recorded so that
   it is checked rather than assumed.)
3. **Stability.** ≥60% of calendar quarters in the window have positive net overnight return,
   mirroring the G1 criterion so a pass here means something at G1.
4. **The effect, not drift.** The overnight leg's gross mean exceeds the intraday leg's gross mean.
   If both legs are equally positive the reading is index drift spread evenly across the day, which
   is not this effect and would not survive being split.

**FAIL**, by first criterion that fails:

- Criterion 2 fails → `inconclusive` (layer 0).
- Criterion 4 fails → `rejected: no signal` — whatever is being measured, it is not the overnight
  premium.
- Criterion 4 passes but 1 fails → `uneconomic`. **The effect is real and we cannot be paid it.**
  Permitted follow-up under §6.2.3 layer 2 is a lower-cost venue or instrument, *not* another US500
  variant — and we do not currently have one, so in practice this closes it.
- Criteria 1, 2 and 4 pass but 3 fails → `regime-dependent`.

## Runs

**Run 1 — 2026-09-19**, `research/experiments/2026-09-19-us500-overnight-premium.py` + `.json`.
US500, 2024-01-02 → 2026-07-31, **665 cash sessions** (median 391 minute bars each), mean spread
0.5553 pts, financing charged per 21:00 UTC cut-off actually crossed. Holdout untouched.

| leg | side | n | gross | spread | financing | **net** | CI95 | quarters + |
|---|---|---|---|---|---|---|---|---|
| overnight | long | 664 | **+3.173** | 0.555 | 1.450 | **+1.168** | −1.616 … +3.896 | 8/11 |
| overnight | short | 664 | −3.173 | 0.555 | 0.046 | **−3.774** | −6.488 … **−0.979** | 2/11 |
| intraday | long | 665 | **+0.974** | 0.555 | 0.000 | +0.418 | −3.034 … +3.930 | 4/11 |
| intraday | short | 665 | −0.974 | 0.555 | 0.000 | −1.529 | −5.041 … +1.923 | 6/11 |

**Verdict: `inconclusive`** (layer 0, power).

**Sanity check performed before trusting any of the above:** the two legs must telescope to the
index's actual move, or the session marks are misaligned. 664 × 3.173 + 665 × 0.974 = **2,754 pts**
against an index move of **2,702 pts** (4,774 → 7,476) over the same window. The 52-pt residual is
the dropped stub sessions and the window edges. The decomposition is sound.

## What the run found

**The documented effect is present on our instrument, clearly.** The overnight leg carries
**+3.173 pts per session against the intraday leg's +0.974** — 77% of the total daily return
accrues in the 27.5 hours the cash market is shut, against 23% in the 6.5 hours it is open.
Criterion 4 passes decisively, so this is the overnight premium and not index drift smeared evenly
across the clock.

**Financing takes 46% of it.** Gross +3.173, costs 2.005 (spread 0.555 + financing **1.450**). The
broker's −0.0215%/day long rate captures very nearly half of the premium it is charged against.
That is the answer to the question this hypothesis was written to ask, and it is worth knowing.

**But the sample can never resolve what is left.** Net +1.168 pts per session sits against a
standard deviation of **36.6**. At 80% power that needs **7,689 sessions — about 31 years** of daily
data. We have 664. The positive net mean is real in this sample and means nothing out of it; the
95% CI runs −1.616 to +3.896.

**One result here is statistically solid, and it is a prohibition rather than an edge.** The
overnight **short** leg nets −3.774 with a CI entirely below zero (−6.488 … −0.979) and only 2 of 11
quarters positive. Holding US500 short overnight is a reliable way to lose money — you pay the
premium instead of receiving it, and the short financing rate (−0.00068%/day) is too small to
compensate. This is a risk control for any future strategy on this instrument, not a trade.

## A correction to this document's own pass/fail mapping

The criteria thresholds were set before the run and have not been moved. The **status label**
mapped to one of them was wrong, and it is corrected here rather than quietly.

Criterion 1 was written as a single test — *net mean > 0 **and** CI lower bound > 0* — which mixes
two of the spec's diagnosis layers into one pass/fail. "Does the move cover spread and financing?"
is layer 2. "Can this sample resolve it?" is layer 0. Because they were fused, criterion 1 failing
could not distinguish the two, and the document mapped that failure to `uneconomic`.

The data separates them cleanly, and it separates them the other way:

- **Layer 2 passes.** Gross +3.173 exceeds costs of 2.005. Costs do *not* eat the edge; they eat
  46% of it and leave a positive remainder. Calling this `uneconomic` would state something the
  data contradicts.
- **Layer 0 fails.** 664 sessions available, 7,689 needed.

Spec §6.2.3 assigns the status to the **first failing layer**, and layer 0 precedes layer 2, so the
correct status is `inconclusive` with the layer-0 follow-up: *park until more data exists*. The run
script now evaluates layer 0 and layer 2 as separate criteria (`2_power` and `1b_costs_covered`) so
this cannot recur.

**`inconclusive` here should not be read as encouraging.** It does not mean "promising, keep
going". It means this specific edge is unresolvable at the sample sizes obtainable: 31 years of
US500 daily sessions is not a data-sourcing task, it is a different project. Parking it is the
honest action, and the parked condition is a longer history, not a better idea.

## Follow-ups this permits, and does not

Layer 0's permitted follow-up is to park until more data exists. Specifically **not** permitted, and
not attempted: re-cutting the same 664 sessions by weekday, by month, by volatility regime, or by
any other filter to find a subset where the CI happens to exclude zero. Every such cut has *less*
power than the whole, and the whole is already 12× short.

The one genuinely different direction the numbers suggest — that the intraday leg is near zero
while the overnight leg carries everything, so the tradeable asymmetry may be in *avoiding* the
intraday session rather than capturing the overnight one — is a restatement of the same
underpowered quantity and inherits the same problem.
