# Axe-Trader: day-by-day review, explain, change, rerun, compare

> Owner's plan (2026-09-25, `docs/superpowers/plans/2026-09-25-us500-day-review-loop.md`), taken verbatim. §13 at the end adds the decisions and verified facts from the clarification round.

## Intent and working pace

I want to slow this project down and understand its decisions. Focus on US500. Let me review one trading day at a time, give feedback on entries and exits, and repeat that day until I am satisfied that the decisions make sense. Then run the resulting strategy unchanged on the next day to see whether the reasoning carries over. Later, expand to several days and progressively larger groups.

Consider a broad range of relevant technical-analysis indicators and price patterns, but introduce trading-rule changes deliberately. Do not turn this into another large automated search through hundreds of variants.

Success for the first milestone is a working feedback loop I can use, not a profitable-looking backtest or a cleaned-up repository.

## 1. Starting context: verify before relying on it

The previous plan reported:

- The Python research engine is the active backtest backend; the Java history importer supplies historical prices.
- The current confluence strategy uses four voting pillars plus a trend gate.
- Existing review pages show trade windows, use browser-local marks, and do not provide a complete feedback-to-rerun loop.
- The research ledger contains many previous variants, with none established as tradeable.
- Candidate reusable components include `build_strategy`, `compute_pillars`, `PillarConfig`, `candle_window`, and `trade_payloads`.

Treat these as starting information, not freshly verified facts. Inspect the current branch, working-tree changes, relevant instructions, engine, data, and review pages. Preserve unrelated work. Briefly report any material discrepancy before designing around it.

Keep the current strategy as the baseline. Do not silently change its rules while implementing the review tooling.

## 2. The review cycle

### A. Review and refine one session

1. Run the baseline on one complete US500 cash session, with sufficient earlier history for indicator warm-up.
2. Show synchronized 5-minute and 15-minute views of the full session, including sessions or periods with no trades.
3. Let me grade entries and exits separately, mark better entry/exit locations, mark missed moves, and mark situations where staying out was correct.
4. Save feedback durably and make it directly available to Claude.
5. Explain the actual decision at each reviewed location, including why no action occurred.
6. Propose one explicit, testable change in plain language. State what should improve and what might get worse.
7. Wait for my agreement on that strategy change. Implement it as a new immutable version and rerun the same session.
8. Compare the new version with its parent and the original baseline. Retain both improvements and regressions in the record.
9. Repeat until I am satisfied with the reasoning and trade-offs. Do not optimize toward perfect historical entries and exits.

Routine implementation of the agreed review tooling does not require repeated approval. Strategy changes and advancing the review to additional days remain deliberate decisions with me.

### B. Move to the next day

1. Freeze the accepted strategy version before inspecting the next session's results.
2. Run that version unchanged on the next preselected chronological session.
3. Save its first-pass result before making any changes informed by that session.
4. Review that day using the same loop.
5. If a change follows, rerun all previously reviewed sessions to identify regressions. The interface can remain focused on the current day while earlier sessions are checked in the background.

Do not skip quiet, losing, or awkward days because they look less useful. Record legitimate exclusions, such as missing or corrupt data.

### C. Expand gradually

Expand the number of days only when I say the workflow and explanations are working for me. No arbitrary number of reviewed days establishes tradeability.

Reserve a separate, untouched evaluation period from the beginning, based on what data has actually been used in prior research. Do not call previously explored history unseen. Any day used to select or adjust rules becomes development data.

Later, evaluate a frozen candidate on untouched data after realistic costs. If those results influence further tuning, those days are no longer untouched; subsequent evaluation needs a new reserved period. Preserve the record of all tested variants. A next-day first pass is useful evidence, but one day is not proof of repeatability.

## 3. Separate sessions, versions, and review iterations

Do not use “round” to mean both a trading date and a strategy revision.

Track these independently:

- **Session:** the trading date and session definition.
- **Strategy version:** immutable rules and parameters, with its parent version and agreed hypothesis.
- **Run:** one strategy version applied to one session and timeframe using a specific data snapshot and engine version.
- **Review iteration:** feedback, explanations, and comparisons attached to those runs.

Each run must retain enough information to reproduce it: configuration, code version and any relevant uncommitted changes, data snapshot or immutable data reference and checksum, warm-up policy, session calendar, timeframe, execution assumptions, costs, and generated decisions/trades.

Feedback must reference the exact run and bar or trade it concerns. New runs must not overwrite old results or silently inherit old grades as if I reviewed them again.

## 4. Indicators: broad observation, deliberate use

Keep the existing voting rules unchanged for the baseline. Add a separate observation layer whose values do not affect execution until an agreed strategy revision explicitly uses them.

Organize observations by the information they provide:

| Family | Initial candidates |
| --- | --- |
| Trend and direction | EMA/SMA levels and slopes, Supertrend, completed higher-timeframe direction |
| Momentum | RSI, MACD, ADX and directional movement |
| Volatility and compression | ATR, Bollinger width, range contraction and expansion |
| Structure and location | Confirmed swing highs/lows, prior-session high/low/close, opening range, breakouts, retests, failed breaks |
| Participation | Relative volume and session VWAP, subject to verified volume semantics |
| Session context | Time since open, overnight gap, scheduled announcements where reliable historical information is available |

The initial observation set can reuse the previous proposal: SMA(20/50), Supertrend(10, 3), MACD(12, 26, 9), ADX/DI(14), session VWAP, and prior-session levels, alongside existing indicators. Add structure and context incrementally. Do not delay the first usable loop until every candidate exists.

Record indicator changes over preceding bars, as well as current readings. Slopes, crossings, persistence, and sequences can matter more than isolated values. Use only information available at the decision time.

An indicator agreeing with my good grades generates a hypothesis. Before using it in execution, also examine bad trades, missed opportunities, and correct no-trade situations. Show actual counts and counterexamples; say when the sample is too small.

Do not treat correlated indicators as independent confirmations. Do not automatically promote an observation into another voting pillar. Depending on the hypothesis, it might become a filter, a setup condition, an exit condition, or remain observational. Test its incremental effect through one agreed change at a time.

## 5. Explain the complete decision

The current strategy may emphasize pullbacks or reversals within a trend. Do not assume every marked breakout or continuation should be captured by loosening its thresholds.

For each entry, exit, or arbitrary marked bar, explain:

- Which setup or rule was being evaluated.
- Every relevant pillar vote and its actual readings.
- Vote count, threshold, and trend-gate result.
- Available position state and any other implemented execution blockers, such as an existing position, timing restrictions, or unavailable indicator values.
- For exits, the actual trigger and position/stop state.
- Relevant watched observations, clearly separated from conditions that caused the action.

For a missed move, distinguish:

1. An existing setup was blocked by identified conditions.
2. A valid signal was blocked by position state or another execution constraint.
3. This setup type is not represented by the current strategy.

Keep explanations grounded in engine-generated traces and shared rule logic. Claude can summarize them, but must not invent a plausible explanation after seeing the outcome. If no convincing pattern is found, say so.

Future setup families might cover pullbacks, breakouts, and reversals separately. Do not build all of them in the first milestone.

## 6. Review interface and feedback

Provide a full-session chart for each timeframe with synchronized navigation, trade markers, and marks anywhere on the session.

Each entry and exit gets an independent grade:

**good · ok · meh · poor · bad**

Ungraded means not reviewed. Add optional short reasons and free text, for example: too late, against structure, exit too sensitive, missed continuation, or correct decision despite loss. A “please grade” flag can identify items Claude wants me to inspect.

Keep decision quality separate from realized profit. A justified trade can lose; an unjustified trade can win. An exit is not automatically bad because the market later continued.

Support:

- A compact explanation next to each marker, expandable into full readings.
- Click-to-mark better entry/exit locations.
- Range marks for missed moves, with direction and an optional intended setup description.
- Correct no-trade marks.
- Before/after comparisons that show unchanged, removed, moved, and newly introduced trades.
- An optional replay mode that hides future candles, including on the companion timeframe, so a proposed decision can be inspected using only information then available.

A marked ideal location is a research prompt, not an executable rule. Any proposed change must identify the observable trigger, possible fill, initial risk, and additional trades it would create elsewhere.

## 7. Durable feedback and reproducible comparisons

The original plan proposed an artifact `db` capability and retrieval through `ArtifactData`. Verify those capabilities in the actual publishing environment before committing the design to them. A static HTML page alone does not establish a shared database.

Prove a minimal round trip early:

**save grade → reload page → retrieve grade from Claude's environment → export it with run identity → publish its explanation → confirm it remains attached to the correct run.**

If the proposed capabilities are unavailable, implement a supported durable backend appropriate to the existing project and explain the choice. Browser-local storage must not be the only copy. Surface failed saves clearly.

Use append-only run history and stable feedback identities. Preserve feedback revisions and produce versioned exports for reproducibility. Republishing the page must not erase old grades, explanations, or comparisons.

When a trade moves or disappears, retain its original feedback. Show explicit matching or unmatched items; do not attach an old grade to a different trade based only on list position. Define the time/price tolerance used to count a marked opportunity as caught.

## 8. Exits and comparison metrics

Treat exits as a first-class research question. Retain current stop, trail, time, and reversal behavior as the baseline; indicator-based exits are candidates for later agreed experiments.

Record the position state needed to explain each exit: entry and fill time, entry price, initial stop and risk, stop updates, trailing activation and level, time in trade, and exact trigger. Distinguish protective stops from discretionary exit conditions.

Change entry logic and exit logic separately wherever feasible. For exit-only comparisons, show matched entries as well as the full rerun, because changed exits can alter later entry availability.

The comparison should show:

- Bad entries removed and good entries retained.
- Good trades lost and newly introduced bad trades.
- Marked opportunities caught, missed, or caught too late.
- Trade count, net result after costs, and drawdown.
- Results relative to initial risk where that risk is defined.
- Maximum adverse/favorable movement during each trade, and profit given back before exit.
- Effects on every previously reviewed session.

Distinguish measures based on my grades from measured trading outcomes. Do not classify new trades as good or bad without review or a clearly stated separate outcome definition. Subsequent price movement can be shown as hindsight context, but must not be presented as information available at exit.

## 9. Data, timing, and execution correctness

- Define the normal cash session as **09:30–16:00 America/New_York**, respecting exchange holidays and early closes. Convert to UTC by date; do not hard-code 13:30–20:00 UTC year-round.
- Specify whether indicator warm-up includes overnight prices or only cash-session bars. Use sufficient prior history and mark unavailable values explicitly.
- Derive 5-minute and 15-minute bars consistently and define their timestamp conventions. The same period count represents different elapsed time on each timeframe; explain that comparison choice. Initially keep the timeframe runs independent.
- Use only completed bars when rules require completed readings. If higher-timeframe information is later used for lower-timeframe decisions, only completed higher-timeframe candles are available.
- Confirm swings only when their required confirmation bars have occurred. Opening ranges and prior-session levels must become available at the correct times. Avoid future-aware pattern labels.
- Separate signal time from fill time. State bid/ask, spread, slippage, fees, and session-end handling. Specify conservative or lower-resolution-supported handling of ambiguous intrabar stop/target ordering.
- Verify what the imported US500 volume represents. Label VWAP/relative volume accordingly; if reliable volume is unavailable, report that limitation rather than fabricating a standard market-volume interpretation.
- Identify missing, duplicated, stale, or malformed bars. Keep both versions of a comparison on the same data snapshot and execution assumptions unless the agreed experiment explicitly changes those assumptions.

## 10. Implementation order

### Milestone 1: one usable end-to-end review loop

Inspect and reuse existing components. Implement only what is needed for:

1. One baseline US500 session, 5-minute and 15-minute full-session views.
2. Honest explanations of current entries, exits, and arbitrary marked bars.
3. Durable grades and marks, with the round trip verified.
4. Immutable strategy/run identities and retained baseline results.
5. One agreed rule change, rerun of the same day, and a before/after comparison.

Check the published page yourself before handing it to me. Do not automatically advance to another date.

### Milestone 2: broader observation and stronger review

Add the remaining initial watched indicators, causal structure observations, replay mode, richer exit analysis, and pattern summaries with counterexamples. Prioritize what helps explain the marks I actually make.

### Milestone 3: next-day first pass and cumulative checks

Freeze the accepted version, record the next day's unchanged first pass, and support reruns of previously reviewed days when changes follow. Expand the review batch only when I request it.

### Milestone 4: larger evaluation

When the process is stable and the sample supports it, evaluate frozen candidates over broader development history and a reserved untouched period after costs. Establish explicit performance and risk criteria before inspecting the reserved results. Do not label a strategy tradeable solely because it matches my reviewed examples.

## 11. Cleanup is separate, not the first milestone

Do only cleanup required to deliver the review loop. Defer broad Java deletion, dashboard removal, historical export deletion, database row deletion/VACUUM, and large-file migration.

Before later cleanup, verify references, preserve recoverable data and a working baseline, and use small explicit commits. Git history is not a backup for untracked database contents. Keep the history importer functional. Follow existing repository rules for commits and pushes; do not mix unrelated deletions into review-loop changes.

Update documentation as behavior changes, but do not make a repository-wide documentation rewrite a prerequisite to the first useful page.

## 12. Verification and acceptance

Use the existing engine tests and relevant importer/build checks. Apply checks appropriate to each change; Java checks are required when Java code or shared dependencies are affected.

Add meaningful coverage for:

- Unchanged baseline decisions after extracting explanation/trace logic.
- Explanations matching recorded engine decisions, including exits and no-entry blockers.
- New indicators against independently calculated examples or documented references, including initialization and unavailable values.
- Session boundaries, daylight-saving changes, early closes, and VWAP reset behavior.
- No future-data use: appending future bars must not change prior decisions or the readings that were available then.
- Durable save/reload/retrieval and correct run association after republishing.
- A reproducible baseline/new-version comparison whose counts match inspected examples.
- A saved next-day first pass and detection of regressions on earlier reviewed sessions.

The first milestone is accepted when I can review a complete day, save feedback, see truthful explanations, agree one change, and inspect its consequences against the original without losing history.

## 13. Decisions and verified facts (clarification round, 2026-09-25)

### Decisions (owner)

- **Baseline exits: all four existing arms plus a fifth, the confluence exit.** It shares the frozen entry and exits when the **opposite side's** confluence score reaches the threshold (holding LONG, 3 bearish pillars agree; mirrored for SHORT). The 10 ATR brake stays as the protective stop.
  - It's a new arm class next to the others in `research/engine/engine/strategies/confluence.py`, registered in `ARMS` / `ARM_PARAMETERS` in `engine/run.py`, with tests.
  - It's part of baseline v1, not a strategy change: the owner asked for it before any review.
  - It is not the existing `reversal` arm. That arm exits when the held side's own score drops below threshold, which is almost always the next bar.
  - **UI:** entries are shared across arms, so the page shows one arm's exits at a time through an arm selector (default: confluence exit). This keeps the view uncluttered. Exit grades attach to (run, arm, trade).
- **Session rules: overnight warm-up, and trades may hold overnight.**
  - Indicators use all Capital.com prices, including overnight.
  - Entries: signals are evaluated on every bar, and the day view highlights the 09:30–16:00 NY cash session.
    - **Open item for the first review:** whether entries outside cash hours are allowed. The baseline keeps the engine's current behavior (no time restriction), and the explanation names this as an execution condition.
  - A trade open at the close carries over: the day view extends to its exit and marks it "carried".
  - Financing is charged through the existing `engine/costs.py` overnight fee.
- **Start at the beginning of 2024 and walk forward chronologically.**

### Verified facts

- **Branch and tree:** `feat/confluence-exit-matrix` at `f074524`. The owner's plan file is modified but not committed. The other dirty files (`sqlite.gz`, `output/charts/*`, untracked `scripts/`, `review-spike/`, backup) are pre-existing and left alone.
- **First reviewable session is 2024-01-11, not 2024-01-02.**
  - US500 minutes start 2024-01-01 23:01 UTC, and the engine's `WARMUP_BARS = 600` blocks entries until 600 bars exist.
  - On 15m that is ~9,000 minutes of quotes, reached late on 2024-01-10. On 5m it is reached on 2024-01-03.
  - So 2024-01-11 is the first session where both timeframes are warmed up. Session 1 = **2024-01-11**, and sessions 2024-01-02…10 are recorded as warm-up exclusions.
- **Untouched evaluation period:** all 13 experiment scripts restrict US500 to development data up to 2026-07-31 and name 2026-08-01+ as the holdout. The one script that reads the holdout (`2026-09-19-confluence-direction.py`) is OIL_CRUDE only.
  - The reserved period is therefore **US500 2026-08-01 → 2026-09-17**, plus anything imported later.
  - I'll write it into a `research/rounds/RESERVED.md`-style record before the first run. Review sessions must never enter it.
- **US500 volume is Capital.com `last_traded_volume`, a per-minute count of quote updates** (e.g. 60–130 per minute at the open), not exchange volume.
  - The existing Vol+Trend pillar and any VWAP or relative volume are **tick-activity weighted**, and the UI labels them that way.
- **`db` capability and `ArtifactData`** are both available in this environment (capability roster lists `db`; `ArtifactData` is a deferred tool). The §7 round trip still has to be proven in step 3 below before anything is built on it.
- **Session calendar:** NYSE holidays and early closes come from `pandas_market_calendars` (added to the engine venv). There's no hand-kept list.

### Milestone 1: concrete steps

1. **Commit the owner's plan** (explicit path only) and close the 2026-09-19 handover's next action as "decided: change the question".
2. **Engine additions**, test-first in `research/engine/tests/`:
   - session calendar module: NY cash session → UTC per date, holidays, early closes;
   - the confluence-exit arm;
   - a **decision trace** recorded by the simulator on every bar: votes, readings, gate, position state, stop and trail levels, exit trigger;
   - `explain_bar()` built from that trace, never recomputed after the fact;
   - a test that baseline trades are identical before and after tracing is added.
3. **Round-trip spike:** a tiny page declaring `db` saves one grade, reloads, and I read it back via `ArtifactData`. This proves §7 before the real page exists.
4. **Run records:** `research/review/runs/<run_id>/` (append-only) holds `strategy_version.yaml`, data checksum, engine commit, costs, trades and trace. `research/review/strategies/v001.yaml` is the baseline (current `PillarConfig` + five arms).
5. **Day review page** `research/review/day-review.html`: 5m and 15m synchronized full-session charts with an arm selector, marker chips that expand into readings, grades and reasons, the mark tools, and `db`-backed storage keyed by run_id + trade/bar. Session 2024-01-11, baseline v001. I publish it and check it before handing it over.
6. **Ingest and explain:** feedback exported to `research/review/feedback/<session>/<iteration>.json` (committed), explanations published back.
7. **One agreed change** → v002 → rerun 2024-01-11 → before/after comparison with explicit trade matching.

Commit and push after each step, staging explicit paths only.

## References behind the review constraints

- NYSE session hours, holidays, and early closes: https://www.nyse.com/trade/hours-calendars
- Bailey et al., *The Probability of Backtest Overfitting*: https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf
- Capital.com API fields and historical-price documentation: https://open-api.capital.com/
