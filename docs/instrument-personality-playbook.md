# Instrument Personality Playbook

**Status: active ground rules.** This is the repeatable exercise every instrument goes through to
find its "personality" (see `CLAUDE.md` → Trading Goals). US500 is the first instrument through it;
the process, not the US500 numbers, is what we're locking in. When a second instrument starts, it
runs this same loop from the top — do not assume US500's thresholds transfer.

Read `CLAUDE.md` (Trading Goals + Tuning Workflow) and `TODO.md` (tuning log) first. The exits work
this playbook wraps is spec'd in `docs/observability-and-exits-design.md`.

---

## Why this doc exists

We have a real problem and a real asset. The asset: an ~80% win-rate entry edge on US500 that holds
out-of-sample. The problem: it's net-negative under honest fills because the exit geometry throws the
edge away, and **every entry-side filter we've tried to fix it has failed out-of-sample** (the
near-EMA "90% win" lead cratered from +1.43 IS to −2.02 OOS — iteration 10). We keep overfitting to
in-sample noise.

The fix isn't more clever filters found by grid-search. It's a **disciplined human-in-the-loop loop**:
use our eyes (via an interactive visual tool) to *propose* hypotheses, and use the full dataset with
out-of-sample discipline to *dispose* of them. This doc is the ground rules for that loop, so a fresh
session — or a second instrument — runs it the same way and can't relapse into the overfitting trap.

---

## Development method: spec-driven (SDD)

**We build spec-first.** Before writing non-trivial code:

1. Write or update the spec — the *what* and *why*, the acceptance/validation criteria, the
   interfaces — in the relevant `docs/*.md` (or this playbook) and get it agreed.
2. Implement against the spec. The spec is the source of truth; if reality forces a change, update
   the spec in the same change, don't let code and spec drift.
3. Every strategy iteration still follows the Tuning Workflow: one small commit per iteration, push
   after every commit, log the result in `TODO.md`. The spec says what we're testing *before* we see
   the number, which is itself an anti-overfitting guard (you can't move the goalposts after the fact).

New behaviour that isn't captured in a spec doc first is a process miss, not a shortcut.

---

## The visual review tool (core instrument, not a nice-to-have)

Interactive trade-review view, delivered as a self-contained Claude Artifact (inline HTML/SVG/JS, no
external CDNs — Artifact CSP blocks them). Spec details in `observability-and-exits-design.md` §3.
It is the hypothesis-generation engine for the loop below, so it is a first-class deliverable.

Must-haves:
- **KPI tiles** — win%, net expectancy (points **and** $/contract), trades/day, worst-quarter net,
  max drawdown. "Does it make money" answerable at a glance.
- **Trade gallery** — per-trade candle cards, **50 bars before entry + 50 after exit**, with
  entry/exit markers and stop/target lines drawn on. The "after" window is what lets you see *what the
  algo missed* — did price hit target right after we stopped out; did a capped winner keep running.
  Filterable to losers / winners / by-cause; sample winners so it's not thousands of cards.
- **Conditional-slice panel** — win%/net per feature bucket **vs. the baseline** (never a
  distribution over losers; see the base-rate rule below).
- **"Flag as hypothesis" capture** — flag a trade and it records a *note plus the feature vector at
  that entry bar* as a hypothesis to test. It must **not** record a "correct answer" label the model
  fits toward. The output of eyeballing is a testable rule, never training data. (See ground rules.)

Self-describing cards: each card embeds config slug, git commit, entry/exit time+price, pnl (pts+$),
R, exit_reason, and the entry feature vector as visible text, so one screenshot is a complete bug
report.

---

## Ground rules for the human-in-the-loop (non-negotiable)

These are the guardrails that make "look at what the algo missed and feed it back" a strength instead
of the overfitting trap. They apply to every instrument, every iteration.

1. **Eyes propose, data disposes.** Your eyes are a hypothesis *generator*, never a validator. A
   pattern you spot in the gallery is a candidate to test, nothing more — it might be gold, it might
   be garbage, and only the data decides which.

2. **A flagged trade is a hypothesis, not a label.** "Hey, look what the algo missed here" feeds in as
   a *candidate rule to consider*, never as a correct answer the algorithm is tuned to match. The
   moment you fit toward hand-marked points, or validate a change by "does it now match more of my
   flags," the loop is circular and you're overfitting. Feeding a hint in as suggestive rather than
   instructive does **not** make it safe — the safety comes entirely from the validation gate below,
   not from how gently the hint is offered.

3. **Backward-only, or it doesn't count.** Every hypothesis must be cashed out as a rule computable
   from data available **at the entry (signal) bar** — no future bars. "What the algo missed" is
   defined using the future, so a raw hint is contaminated with look-ahead until you strip it to what
   was visible at the time. Same discipline as `BacktestRunner.reasonsAt` / `featuresAt`.

4. **Every hint earns its place out-of-sample, with zero privilege.** A human-spotted hypothesis gets
   the *identical* validation as a machine-generated one: improves net expectancy in-sample across
   **every quarter/regime**, then survives **one** out-of-sample shot with no retuning. No free pass
   for "a human noticed it" or "it's obviously right."

5. **The base-rate rule.** The actionable signal is always *win%/expectancy conditional on a feature
   bucket vs. the baseline* — never a distribution over losers. Not "70% of losers were near the EMA"
   but "trades near the EMA win 52% vs 80% baseline."

6. **Spot-checks generate ideas; mass data kills noise.** Noise is beaten by *more* samples and honest
   out-of-sample tests, not by fewer curated ones. Small hand-picked samples are what *create* phantom
   patterns. If a decision rests on a handful of trades, it isn't decided yet.

### Why the human loop actually helps (the real mechanism)

It helps through **shrinking the search space**, not through trusting the eye. Grid-searching 10,000
filters guarantees a few pass out-of-sample by luck (multiple-comparisons — this is what burned
iteration 10). Eyes that narrow the field to three candidates make a pass far more likely to be real,
because you tested three things, not ten thousand. The eye's value is the *shorter list of things
worth testing* — the hard out-of-sample gate is unchanged and non-negotiable.

Worked example (both real): eyeballing losers, you notice "these dump right after a fat volume-spike
bar." Cash it out as `volume_ratio > 2.5 at entry` (backward-only ✅), test in-sample every quarter,
one OOS shot. The data already hints this one **passes** (volume >3.2× → 25% win vs 80% baseline).
The near-EMA hunch, run through the same gate, **failed**. Same tool, same gate — the gate is what
separated signal from garbage, not the strength of the hunch.

---

## The per-instrument loop (run top-to-bottom for each instrument)

Each instrument is its own "personality" exercise. Lock the *process*, re-derive the *numbers*.

1. **Data.** Load history for the instrument; hold out a window that is never looked at during tuning
   as the final out-of-sample referee. (A burned window — one you've inspected — can't referee again.)
2. **Entries.** Establish an entry edge via the 5-pillar confluence; validate the win rate holds
   out-of-sample. This is necessary but *not sufficient* — win rate alone is a misleading objective
   (see `TODO.md` iterations 3–4).
3. **Honest pnl.** Confirm expectancy under intrabar fills at the stop/target level (not close-based),
   net of spread, translated to $/contract. Win rate that doesn't survive as positive net expectancy
   is not an edge yet.
4. **Exits.** Fix the geometry so the edge is actually banked — currently the 3-tier scale-out
   experiment (`observability-and-exits-design.md` §4). This is the active money lever.
5. **Visual review + hypothesis loop.** Use the tool + ground rules above to generate and test
   refinements. Gate every candidate on: net ≥ 0 every quarter in-sample, then one OOS shot.
6. **Lock the profile.** When net expectancy is positive across regimes in- and out-of-sample, promote
   the config as that instrument's profile (per-instrument config, not the shared US500 block) and
   record it in `TODO.md`.
7. **Risk controls before any live/demo trading** — position sizing, max drawdown, circuit breaker.

Cadence toward ~5 trades/day comes from running this loop on **several instruments in parallel**, each
at ~1 quality trade/day — never from loosening one instrument's filters.

---

## Roles (who does what — see `.claude/CLAUDE.md` for the routing detail)

This work spans "very hard to reason about" (does this filter overfit? is this loss cluster a regime
or noise?) and "mechanical" (wire a config knob, render an SVG card). Match the brain to the problem:

- **Opus — director / coordinator, and thinker until it needs a specialist.** Runs the loop, holds the
  ground rules, does day-to-day judgment and design, decides *what* to test and *whether a result is
  trustworthy*. Delegates down for mechanical work; **calls in Fable when the reasoning gets genuinely
  hard.**
- **Fable — the specialist.** Escalate here for the hardest reasoning: is this edge real or overfit,
  statistical-validity calls, exit-geometry / risk-of-ruin math, concurrency or money-handling logic,
  non-obvious performance. When something "seems very difficult to figure out," that's the signal to
  call Fable rather than grind it at a lower tier.
- **Sonnet — the code monkey.** Routine implementation from a clear spec: a feature/knob following
  established conventions, an obvious-cause bug fix, tests needing some coverage judgment.
- **Haiku — boilerplate.** No-judgment mechanical work: pattern-mirroring, CRUD/DTOs, formatting,
  mechanical renames, docs for code that already exists.

Rule of thumb: **delegate down as far as the task can safely go; escalate up the moment real judgment
or hard reasoning is at stake.** After any delegation, read the diff and run tests before accepting —
don't just check that it ran.
