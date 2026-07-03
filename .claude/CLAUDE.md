## Model routing

Four workers cover the ladder: haiku-worker, sonnet-worker, opus-worker, fable-worker. Size each subtask and delegate to the lowest one that can handle it without real risk of getting it wrong — unsure between two, pick the higher one.

- haiku-worker — no judgment needed: boilerplate mirroring an existing pattern, CRUD/DTOs copying something that already exists, tests for logic that's already fully designed, formatting/lint/rename-by-pattern, docs for code that already exists.
- sonnet-worker — clear spec, some shape to fill in: a typical feature/endpoint following established conventions, a bug fix with an obvious cause, tests needing some judgment about coverage.
- opus-worker — real judgment: debugging where the cause isn't obvious yet, code review, filling gaps in a vague request, day-to-day design decisions.
- fable-worker — the hardest problems: architecture calls with real tradeoffs, concurrency/race-condition bugs, security or money-handling logic, non-obvious performance work.

Skip delegating to a worker at or above your own session's tier — nothing to gain. Main session defaults to Opus, so opus-worker only matters when the session itself is Fable; fable-worker is the escalation path otherwise.

After any delegation, read the actual diff and run tests before accepting it — don't just check that it ran. Fix small issues yourself rather than re-delegating.
