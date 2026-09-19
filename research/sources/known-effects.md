# Known effects — candidate hypothesis sources

Spec §6.2.1 source 1: *documented effects with an economic reason and an identifiable losing side*.
Compiled 2026-09-19, after [[H-0001-rsi-bb-mean-reversion]], [[H-0002-short-horizon-continuation]]
and [[H-0003-continuation-longer-horizon]] exhausted the archived Java engine as an idea source.

## How to read this, and its one honest limitation

Every entry records: the effect, the **economic reason** it should exist, the **losing side** — who
pays, and why they keep paying — the instruments and timeframes we can actually test it on, the
**power** we have for it in our 2024-01-01 → 2026-07-31 development window, and a confidence label.

**The citations are compiled from knowledge, not fetched.** Author and year are given so each can be
checked, and no page numbers or exact figures are quoted, because a fabricated decimal is worse than
an absent one. Treat every citation as a pointer to verify, not as evidence in itself. The evidence
is the run, and nothing here is privileged by having a famous name attached — spec §6.2.1 source 2
says published strategies are "candidates only; no privilege", and the same applies here.

**The losing-side test is the filter that matters.** An effect with no identifiable loser is a
pattern in a sample, and patterns in samples do not survive out of sample. Where the losing side is
"nobody in particular", the entry says so and is ranked below the ones where it is a specific,
structurally-constrained participant.

## Aimed by the cost reality check

[[2026-09-19]] (`research/cost-reality/2026-09-19.md`) ranked every instrument × timeframe we hold
by the share of a typical bar's range one round trip consumes. The four cheapest grounds are
**OIL_CRUDE 1d (2.4%)**, **US500 1d (3.0%)**, **US500 4h (3.5%)** and **OIL_BRENT 1d (4.0%)**.
US500 5m — where all three dead hypotheses were tested — is 13th of 19 at 20.7%.

Candidates below are therefore only listed where they can be tested at 4h or slower. An effect that
lives at 5-minute resolution has to be about five times larger to be worth the same money, and we
have no evidence any effect here is that large.

## Instruments we hold

US500, OIL_BRENT, OIL_CRUDE (all pass data quality); NATURALGAS (fails, usable ~11:00–19:00 UTC only).
Effects requiring FX, gold, US100 or an options surface are listed at the bottom as blocked.

---

## A. US500 overnight vs intraday return concentration

**Confidence: high (many independent replications).**

**The effect.** Essentially the entire long-run return of US equity indices accrues *overnight*
(previous close → open), while the intraday session (open → close) contributes approximately zero
or is negative. Documented for US indices and ETFs over many decades.

**Citations.** Cliff, Cooper & Gulen (2008), *Return Differences between Trading and Non-Trading
Hours*; Lachance (2015) on the overnight/intraday decomposition of ETF returns; Bogousslavsky
(2021), *The Cross-Section of Intraday and Overnight Returns*. Widely re-derived since.

**Economic reason.** Overnight is when the market cannot be traded but risk is still held. Someone
must carry that risk across a closed book, and they are compensated for it. Intraday liquidity
providers systematically flatten into the close rather than carry inventory overnight, which
transfers the position — and its premium — to whoever will hold it.

**Losing side.** Intraday market makers and short-horizon traders who are unwilling or unable
(capital, risk limits, mandate) to carry overnight inventory. They pay the premium away to get flat.
This is structural and does not go away, because the constraint is a risk limit, not a mistake.

**The reason this is the first thing to test here, and not elsewhere.** On a CFD you *pay* to hold
overnight: US500's long financing rate is −0.0215%/day, which the cost reality check measured at
**1.30 pts per night against a 66.8-pt median daily range**. That is precisely the cost the overnight
premium is supposed to be compensating. So this is not a search for a pattern — it is a head-on,
falsifiable question with a number on both sides: **does the documented overnight premium on US500
survive a retail CFD's financing rate, or is the financing rate set to capture exactly it?**

Both answers are worth having. If it survives, it is the cleanest edge available to us. If it does
not, it explains in one measurement why every long-biased idea on this instrument has struggled,
and it closes a whole family of them.

**Test.** US500, daily bars. Decompose each day into overnight (previous close → open) and intraday
(open → close) return, net of the actual financing charge for holding across the 21:00 UTC cut-off.
Compare against the shifted-placebo method. Note the CFD's "open" is not the US cash open — the
instrument trades ~23h, so the decomposition must be defined against the 21:00–22:00 UTC daily break
in `instruments.yaml`, not against 14:30 UTC. **Getting that definition right is the whole
experiment**; a careless one measures a different effect.

**Power.** ~660 trading days in the development window. An always-on daily effect gets every one of
them, which is the best power available in this list.

---

## B. Brent–WTI spread mean reversion (OIL_BRENT vs OIL_CRUDE)

**Confidence: high on the cointegration; medium on it being tradeable at our cost.**

**The effect.** Brent and WTI are two grades of the same commodity and their price difference is
bounded by the cost of physically moving and storing oil between the two delivery points. The spread
wanders but is mean-reverting around a slow-moving level; dislocations beyond transport cost close.

**Citations.** The Brent–WTI spread and its structural break around 2011 (US shale, Cushing
bottleneck) is extensively documented in energy-economics literature; see work on WTI–Brent
cointegration and the Cushing storage constraint. Cointegration of crude benchmarks is one of the
more robustly replicated relationships in commodities.

**Economic reason.** Physical arbitrage. If the spread exceeds transport + storage, it pays someone
to move barrels, and that trade closes the gap. The bound is a real cost, not a statistical artefact.

**Losing side.** Whoever is forced to transact in one benchmark at a dislocated moment — hedgers
with a mandate in a specific grade, index roll flow, and anyone liquidating in size in one leg. They
pay the dislocation to whoever is willing to warehouse the spread risk.

**Why it is attractive here.** It is the only candidate in this list that uses **two instruments we
both hold at minute resolution**, and it is close to market-neutral, which sidesteps the index drift
that has contaminated every raw-mean reading so far (see [[H-0003-continuation-longer-horizon]],
where a longer horizon inflated every long-side mean for free).

**The cost that decides it.** A spread trade pays **both** legs: 0.0351 + 0.0414 = **0.0765 pts**
per round trip. The dislocation must exceed that before anything is collected, and it is a *fixed*
cost against a spread whose typical daily move is small. This is the one number to measure first,
before building anything: what is the distribution of the Brent−WTI spread's daily change, and what
fraction of it exceeds 0.0765 pts? If the answer is "very little", the idea is dead in one hour and
costs nothing further.

**Test.** Daily and 4h. Both instruments are USD, so no FX conversion. Watch for the two series'
minute grids not aligning — resample both to the same clock grid and drop any bar missing from
either, rather than forward-filling, which would manufacture false dislocations.

**Power.** ~660 daily and ~4,000 4h observations, but the *events* (dislocations beyond the cost)
will be far fewer, and that count is the real sample size. Expect a layer-0 power problem and
measure it before running anything.

---

## C. Scheduled inventory release reaction on oil (EIA, Wednesday)

**Confidence: medium — the release is real and scheduled; the tradeability is unproven.**

**The effect.** The US EIA publishes its Weekly Petroleum Status Report every Wednesday at 10:30 ET
(15:30 UTC, shifting to Thursday after a Monday US holiday). It moves crude sharply and on a known
schedule. Candidate behaviours: pre-release volatility compression, post-release directional
continuation, or a fade of the initial move.

**Citations.** Scheduled-announcement effects in commodity markets, and the pre-FOMC drift
literature (Lucca & Moench 2015) as the closest well-replicated analogue in another asset class. The
EIA-specific reaction is trade-press documented rather than heavily academically replicated — weaker
ground than A or B.

**Economic reason.** A scheduled information release forces position adjustment at a known moment.
Participants who must be flat or must hedge into the print pay whoever supplies liquidity through it.

**Losing side.** Hedgers and funds with a mandate to be neutral across the release. Identifiable, but
less structurally locked-in than A's intraday market makers.

**Test.** OIL_CRUDE first (cheaper than Brent at every timeframe: 0.0351 vs 0.0414), 4h bars, the
bar containing 15:30 UTC and the following one. **No external calendar is needed for a first look** —
"Wednesday 15:30 UTC" is right for the large majority of weeks, and the US-holiday shift can be
treated as noise that *weakens* the measured effect rather than inventing one. If a first look is
promising, sourcing the real release calendar becomes a task (spec §6.2.1 flags this).

**Power.** ~135 Wednesdays in the development window. This is thin. Compute the layer-0 power
requirement before running; it may well come back `inconclusive`, and that is the correct answer
rather than a reason to loosen anything.

---

## D. Turn-of-the-month effect on US500

**Confidence: medium — long-documented and it survived publication, but our window is far too short.**

**The effect.** Equity index returns concentrate around the turn of the month — roughly the last
trading day and the first three of the new month — with the rest of the month contributing little.

**Citations.** Ariel (1987), *A Monthly Effect in Stock Returns*; Lakonishok & Smidt (1988);
McConnell & Xu (2008), which found the effect persisting long after its publication, unusually for a
calendar anomaly.

**Economic reason.** Flow, not information: salary-cycle contributions, pension and 401(k) inflows,
and month-end index rebalancing all cluster at the same few dates.

**Losing side.** Weak — this is the entry where honesty requires saying there is no specific loser.
Nobody is structurally forced to sell into the turn of the month; the flows are simply predictable.
That makes it more vulnerable to being arbitraged than A or B, and it is ranked below them for that
reason and not because the historical evidence is weaker.

**Power — this is disqualifying for now.** Our window contains about **31 month-turns**. Thirty-one
observations cannot distinguish a real calendar effect from noise at any useful confidence. Under
spec §6.2.3 layer 0 this is `inconclusive` before it starts. **Recorded, not scheduled.** It becomes
testable only with a much longer history, which is a data-sourcing decision, not a research one.

---

## E. Opening-range behaviour on the US session

**Confidence: low — widely repeated in trade material, thinly replicated independently.**

**The effect.** The range of the first period after the US cash open (14:30 UTC in summer, 15:30 in
winter — DST-dependent, and `instruments.yaml` records hours in the current DST state only) sets a
band whose break is followed by continuation for the rest of the session.

**Economic reason.** Overnight information is incorporated at the open, and institutional orders
worked through the day create sustained one-directional pressure. Plausible but generic.

**Losing side.** Not clearly identifiable. This is the weakest entry on that test.

**Why it is listed last despite being popular.** It requires intraday resolution — a "first 30
minutes" rule cannot be expressed on 4h bars — which puts it on 15m or 5m ground where the cost
reality check says a round trip eats 11.7–20.7% of a typical bar's range. It has the weakest
economic story *and* the most expensive ground. It should not be tested before A, B or C.

---

## Blocked — no data

| effect | blocked on |
|---|---|
| London 4pm FX fix | no FX instrument imported (EURUSD, GBPUSD, USDJPY are in `instruments.yaml`, not in the database) |
| Asian-session FX range breakout | same |
| Gold/USD relation | GOLD import was killed mid-staging; staging file is resumable, ~5h cost |
| US500 vs US100 lead-lag | US100 not imported |
| Crude term structure / carry | needs the futures curve; Capital.com gives spot only |
| Oil roll/expiry flow | needs the synthetic's roll schedule; not published in `instruments.yaml` |

Each of these costs roughly 5–6 hours of import time per instrument (see the 2026-09-18 handover's
landmine on the 12-instrument seed). None should be started before A and B have been run on data we
already hold.

---

## Recommended order

1. **A — US500 overnight vs intraday, net of financing.** Best power in the list (~660 days),
   cheapest ground bar one, strongest losing side, and it asks a question with a definite answer
   either way. It also settles whether holding US500 long overnight on this broker is structurally
   a losing proposition, which bears on every future long-biased idea on the instrument.
2. **B — Brent–WTI spread**, starting with the one-hour measurement of whether daily spread changes
   even exceed the 0.0765-pt two-leg cost. Kill it cheaply or promote it.
3. **C — EIA Wednesday on OIL_CRUDE**, expecting a layer-0 power problem and prepared to record
   `inconclusive`.
4. **D and E — recorded, not scheduled.** D is out of power; E has the weakest rationale on the
   most expensive ground.
