## Development method: spec-driven (SDD)

Build spec-first. Before non-trivial code, write/update the spec (the *what*, *why*, and
validation/acceptance criteria) in the relevant `docs/*.md` and get it agreed, then implement against
it; keep spec and code in sync in the same change. For strategy work this doubles as an
anti-overfitting guard — the spec states what we're testing *before* we see the number, so the
goalposts can't move after the fact. See `docs/instrument-personality-playbook.md`.

## SDD execution: per-task review is mandatory

When executing a plan with subagent-driven development, **every task gets its own reviewer before
the next task is dispatched** — never batch reviews to the end, never skip one because a task
"looks mechanical." Both verdicts (spec compliance *and* code quality) are required.

Why: implementers are told to follow the plan verbatim, so a defect in the plan gets faithfully
transcribed into the code. In this repo that has already caught, on tasks that reported clean:
a URL built from a `signal_key` containing `|` unencoded (would have broken every chart load one
task later), frontend types claiming non-null for nullable D1 columns, and two npm scripts that
could not work as written. The per-task reviewer is the only thing between a plan defect and
several tasks of downstream debugging.

Corollary: when a finding is **plan-mandated**, it is the human's call — present the finding and
the plan text and ask which governs. Don't let the plan grade its own work, and don't silently
"fix" the plan either.

## Model routing

**The team, by role:** Opus is the **director/coordinator** — it runs the work, holds the ground
rules, does day-to-day judgment, and is the thinker *until the reasoning gets genuinely hard, at which
point it calls in Fable as the specialist*. Fable is the **specialist** for the hardest reasoning
(is this edge real or overfit, statistical-validity and risk-of-ruin/exit-geometry math,
concurrency/money-handling, non-obvious performance) — when something "seems very difficult to figure
out," escalate rather than grind it at a lower tier. Sonnet is the **code monkey** (routine
implementation from a clear spec). Haiku is **boilerplate**. Match the brain to the problem: delegate
down as far as the task safely goes; escalate up the moment real judgment or hard reasoning is at
stake.

Four workers cover the ladder: haiku-worker, sonnet-worker, opus-worker, fable-worker. Size each subtask and delegate to the lowest one that can handle it without real risk of getting it wrong — unsure between two, pick the higher one.

- haiku-worker — no judgment needed: boilerplate mirroring an existing pattern, CRUD/DTOs copying something that already exists, tests for logic that's already fully designed, formatting/lint/rename-by-pattern, docs for code that already exists.
- sonnet-worker — clear spec, some shape to fill in: a typical feature/endpoint following established conventions, a bug fix with an obvious cause, tests needing some judgment about coverage.
- opus-worker — real judgment: debugging where the cause isn't obvious yet, code review, filling gaps in a vague request, day-to-day design decisions.
- fable-worker — the hardest problems: architecture calls with real tradeoffs, concurrency/race-condition bugs, security or money-handling logic, non-obvious performance work.

Skip delegating to a worker at or above your own session's tier — nothing to gain. Main session defaults to Opus, so opus-worker only matters when the session itself is Fable; fable-worker is the escalation path otherwise.

After any delegation, read the actual diff and run tests before accepting it — don't just check that it ran. Fix small issues yourself rather than re-delegating.
