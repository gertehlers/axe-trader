---
date: 2026-08-08
status: open
branch: feature/delta-price-import
head: 0aeb81f
next: Decide whether to fix HistoricalSessionCalendar (Phase 1.5) — no discovery report can be non-empty until it lands; then Section 4
---

# Axe-Trader Research Agent — design in progress

A brainstorming session (`/superpowers:brainstorming`) turning a "Head Quantitative
Researcher" charter into a durable research agent. **No code and no spec file have been
written yet.** The output so far is four locked decisions and three approved design sections
(1, 2 and 3), with Section 4 next.

> **Correction, 2026-08-08 (second session).** The first version of this document claimed
> the clean price dataset "was never promoted" and that `TODO.md` was wrong about it. That
> was a misreading, and every conclusion drawn from it — including the doubt cast on
> Section 2 — was unfounded. `data/*.sqlite` is gitignored (`.gitignore:6`), so the clean
> database was never meant to travel through git; a checkout that lacks it proves nothing.
> The promotion did happen, exactly as `TODO.md` records. The work has since moved to the
> worktree that holds the data. Details in the first landmine below.

## Where this stands

**Done — four decisions locked by the owner:**

1. **Build it, don't be it.** Produce a durable, reusable research agent rather than a
   one-off analysis.
2. **Full charter as written.** All seven charter responsibilities (analyse results,
   generate hypotheses, suggest filters, study exits, study missed opportunities, hindsight
   research, detect overfitting) belong to the agent. The owner chose this over a narrower
   "feature proposer + report reader" remit after being shown that `ShallowRuleMiner`
   already implements much of it. One constraint was added and accepted: numeric claims
   must come from the backtest engine, not the agent's own arithmetic.
3. **Persistent hypothesis ledger**, not one-shot reports — status tracked across runs so
   falsified ideas are not re-proposed.
4. **Agent triggers its own runs** via a new CLI entry point.

**Done — approach chosen:** "B with a real run first". Build the entry point, produce one
real discovery report, then design the ledger against observed output rather than guesses.

**Done — Section 1 approved.** Architecture and the three-phase split, including the specific
decision to add `DISCOVERY` as a fourth `AxeTraderMode` value rather than a separate CLI
concern.

**Done — Section 2 approved 2026-08-08** (re-presented after the retraction below; the owner
had invoked `/handover` instead of answering the first time). The `DISCOVERY` entry point:

- **Trigger:** `--axe-trader.mode=discovery` plus a new `DiscoveryRunner implements
  ApplicationRunner, ExitCodeGenerator`, gated by `@ConditionalOnProperty`, modelled on
  `HistoryImportRunner`. `AxeTraderMode.from` needs no change — `"discovery"` already parses,
  and `isLiveMarketMode()` returns false so the live runner stays off.
- **Window:** development is `2024-01-01 → 2026-01-01` (24 months). `DiscoveryWindowPolicy`
  gets **extended to also reject any window touching 2026-05-02 onward**, making the tail an
  enforced reserve rather than a convention. See the window landmine below for why this is
  needed.
- **Request population:** nine fields mechanical from `BacktestProperties` and the window;
  `market` from `HistoricalPriceRepository.findByEpicAndSnapshotTimeUtcBetweenOrderBy
  SnapshotTimeUtcAsc` → `BarSeriesFactory.fromPricesWithSides`, deliberately bypassing
  `BacktestProperties.limit` because discovery is window-bounded, not row-bounded;
  `persistencePath`/`reportPath` left null so the record supplies its own defaults.
- **Provenance:** `sourceCommit` from `git rev-parse HEAD`. `inputDataHash` is a **logical
  content digest** — row count, min/max timestamp, and a hash of the ordered
  `(snapshot_time_utc, OHLC)` tuples in the window — **not** the SQLite file hash.
- **Failure behaviour:** fail before the pipeline starts, exit 1 via `ExitCodeGenerator`.
  Reject a protected-window overlap, an empty or short series, and a dirty tree — where
  "dirty" means `src/` or `application.yaml`, ignoring `output/` because `./mvnw test`
  rewrites tracked files there. Record the full `git status` string in the report regardless.
  Do not re-implement one-shot protection; `DiscoveryStore.window_spent` already has it.

**Done — Section 3 approved 2026-08-08.** The hypothesis ledger:

- **Two levels of identity.** Candidate identity already exists — `CandidateRule.create` sets
  `id = sha256(canonicalJson(direction, sortedClauses))`. The ledger adds a coarser
  **hypothesis key** over `(direction, sorted [(feature, operator)])` with **thresholds
  excluded**.
- **Why the coarser key is mandatory.** `ShallowRuleMiner` takes thresholds from
  `ConditionalSliceAnalyzer.thresholds(scores, feature)` — they are *derived from the data*.
  A window shifted by a month re-mints the same idea at `rsi14 < 24.7` instead of
  `rsi14 < 25.1`: different canonical JSON, different sha256, different `id`. Exact-id dedup
  therefore **cannot** satisfy decision 3, because it waves through unlimited near-misses of
  an already-falsified idea. Threshold bucketing by quantile is the principled refinement and
  is deferred until real feature distributions exist; starting coarse over-suppresses, which
  is the visible and correctable failure mode.
- **Lifecycle:** `OPEN → SUPPORTED | FALSIFIED`, plus `PROMOTED` and `SUPERSEDED`. Every
  transition must cite a `DiscoveryRun.runKey()`, the triggering candidate id, and the
  statistic. This is what structurally enforces the locked constraint that numeric claims come
  from the backtest engine rather than the agent's own arithmetic.
- **Suppression is not deletion.** A candidate whose hypothesis key is `FALSIFIED` is withheld
  from the proposal list but recorded as a *sighting*. Repeated sightings of a dead hypothesis
  are themselves signal about the feature set.
- **Storage:** new tables inside `experiments/discovery.sqlite`, not a separate file, so a
  transition and the run justifying it commit in one transaction.

**Not started:** Section 4 onward (the agent definition and its run loop, error handling,
testing), the spec document, the implementation plan.

**The dataset, for reference.** Section 2 assumed a 2024-onward dataset and proposed a
two-year development window of 2024-01-01 → 2026-01-01, holding the tail in reserve. Working
in this worktree, that is exactly what is on disk:

| Checkout | Rows | Range |
| --- | --- | --- |
| `.worktrees/delta-price-import` (**here**) | 917,650 | 2024-01-01T23:01Z → 2026-08-06T19:55Z |
| `.worktrees/clean-local-price-history` | 912,132 | 2024-01-01T23:01Z → 2026-08-02T22:52Z |
| `main` checkout, and the committed `.gz` on every branch | 500,000 | 2024-12-04T23:20Z → 2026-05-01T08:25Z |

With `DiscoveryWindowPolicy`'s protected window at 2026-01-01 → 2026-05-02, this worktree
gives a full two-year development window plus a 2026-05-02 → 2026-08-06 tail that Section 2
turns into an enforced reserve.

## Next action

Design **Section 4** — the agent definition itself, its run loop, error handling, and how
any of it gets tested. Then the brainstorming skill's flow: finish sections → write the spec
to `docs/superpowers/specs/2026-08-08-<topic>-design.md` → commit → self-review → owner
reviews → invoke `writing-plans`. **Do not write implementation code before the owner
approves the spec** — the only code written so far is the one-word `main` build fix, which
was a prerequisite, not part of the design.

Two open threads to fold into Section 4 or the spec: the model tier the agent runs at (still
undiscussed), and the fact that Phase 1 must supply a runner because `AxeTraderMode` is inert
today (see the dormancy landmine).

## Verify current state

Run these **from `.worktrees/delta-price-import`**, not from `main` — the dataset check gives
a different answer per checkout, and that difference is the point.

Observed 2026-08-08 at `82af78f` on `feature/delta-price-import`:

```
./mvnw test
# exit 0 — Tests run: 264, Failures: 0, Errors: 0, Skipped: 3
```

```
./mvnw clean package -DskipTests
# exit 0 since 0aeb81f — 84M jar, Start-Class: io.g3tech.axetrader.AxeTraderApplication
# (still exit 1 on main, which does not have the fix)
```

```
sqlite3 data/axe-trader.sqlite "SELECT COUNT(*), MIN(snapshot_time_utc), MAX(snapshot_time_utc) FROM historical_price;"
# 917650|2024-01-01T23:01:00Z|2026-08-06T19:55:00Z
```

The 264-test count is this branch's (31 history/import tests above `main`'s 233); the build
failure and the dataset are both worktree-local facts. No lint command is documented for this
project and none was run.

## Landmines

**The clean dataset is real, and it does not live in git — work in a checkout that has it.**
`data/*.sqlite` is gitignored (`.gitignore:6`); only `data/axe-trader.sqlite.gz` is tracked,
and `TODO.md` says in as many words that the committed snapshot "is deliberately still the
legacy one." So the clean database exists **per working checkout**, not per branch, and
finding a legacy 500,000-row database in some other checkout is not evidence that anything
failed. It only means that checkout never ran the import.

The promotion recorded in `TODO.md` did happen. This worktree holds 917,650 rows spanning
2024-01-01T23:01Z → 2026-08-06T19:55Z, plus the `price_exclusion` and six `history_import_*`
tables the legacy database lacks. Switching to a checkout without them — `main`, or
`.worktrees/reusable-history-reingestion` — silently swaps a two-year dataset for a
thirteen-month one. `HistoryCursorReader` at least fails closed on the legacy database's
non-canonical `2024-12-04T23:20Z` minute format rather than mis-ordering `MAX()`; the
backtest and discovery paths have no such guard and will simply run on less data.

*An earlier version of this document read this situation backwards and reported the
promotion as never having happened and `TODO.md` as "badly stale on the dataset." Both
claims were wrong.*

**~~`./mvnw clean package` fails~~ — FIXED 2026-08-08 in `0aeb81f` on this branch.**
`AxeTraderApplication.java:21` declared `static void main` (package-private), so Spring
Boot's `repackage` goal could not find an entry point and produced no runnable jar. `public`
had been dropped incidentally in `b1c26ee`. Restored; the build now exits 0 and yields an 84M
jar with `Start-Class: io.g3tech.axetrader.AxeTraderApplication`, suite unchanged at 264/3.
**`main` still carries the bug** — it is fixed only here until this branch merges.

**The `AxeTraderMode` switch is dormant — adding `DISCOVERY` to the enum does nothing on its
own.** `AxeTraderMode` is referenced in exactly one place, `AxeTraderRunner`, and
`AxeTraderRunner.run()`, `.ping()` and `.close()` are never called from anywhere in
`src/main` — nothing wires that `@Service` to startup. `BacktestRunner` is not invoked at
startup either. The only thing that actually drives behaviour on boot is
`HistoryImportRunner` via `@ConditionalOnProperty` + `ApplicationRunner`.

Two consequences. `CLAUDE.md` is wrong that `./mvnw spring-boot:run` runs a backtest — it
runs nothing. And MONITOR mode "not working end-to-end", still open in `TODO.md`, is this
same dormancy rather than an independent bug. Phase 1 must supply the runner that reads the
mode; the enum constant alone is inert.

**`./mvnw test` dirties the working tree.** In this worktree it rewrites two tracked files,
`output/charts/chart.html` and `output/charts/runner-results.html`. This matters more than it
looks: Section 2 proposes that a discovery run record `sourceCommit` and fail closed on a
dirty tree, and running the test suite would trip that check on an otherwise clean checkout.
`git restore output/charts/` clears it.

**`DiscoveryWindowPolicy` protects only the middle band, not the tail.** The guard is
`if (from.isBefore(OOS_TO) && OOS_FROM.isBefore(to)) throw` — it rejects overlap with
`2026-01-01 → 2026-05-02` and nothing else. A window lying entirely *after* the protected
band passes validation happily, so the ~3 months out to `2026-08-06` that the delta import
added are unprotected by default. Section 2 closes this by extending the policy; until that
lands, do not assume the tail is safe just because a window validated.

**SHA-256 of a live SQLite file is not a stable content identifier.** The dataset in
`.worktrees/clean-local-price-history` matches `TODO.md`'s row count and time range exactly
(912,132 rows, 2024-01-01T23:01Z → 2026-08-02T22:52Z) but hashes to `138ef7b2…` rather than
the recorded `e19cbb90…`. Opening a SQLite database can change its bytes, so a drifted hash
over an otherwise-matching database is expected, not corruption. Section 2's proposal to use
the file hash as `DiscoveryRequest.inputDataHash` should be reconsidered in favour of a
digest over logical content — row count, min/max timestamp, and a hash of the ordered rows.

**`TODO.md` is stale on the discovery work** — this one is real, unlike the dataset claim
retracted above. It describes the empirical path-first discovery as active with Task 3 in
progress, Tasks 5–12 unstarted, and four uncommitted tests to preserve in a worktree at
`.worktrees/empirical-path-first-discovery`. In fact the work is complete and merged — 46
files under `src/main/java/io/g3tech/axetrader/backtest/discovery/`, present on `main` and
here, with `94d9759 feat(discovery): add task 10 and 11 dashboard integration` as the most
recent commit touching it. That worktree no longer exists; `git worktree list` shows four
(`main` plus `clean-local-price-history`, `delta-price-import`,
`reusable-history-reingestion`), and the branch survives as
`remotes/origin/feature/empirical-path-first-discovery`.

**The discovery pipeline has never run outside tests.** `DiscoveryPipeline` is referenced
only by `DiscoveryPipelineTest` and `EmpiricalDiscoveryHarnessTest`. There is no
`CommandLineRunner` for it, no `DISCOVERY` value in `AxeTraderMode`, and neither default
artifact exists on disk — no `experiments/discovery.sqlite`, no
`dashboard/discovery-report.json`. The JSON contract the agent is meant to read is
currently produced only by test fixtures.

**`HistoricalSessionCalendar` treats every missing minute as a session boundary, which
excludes ~96% of observations on real data.** `gapsIn` registers *any* gap longer than one
minute as a boundary candidate. Real US500 history is full of single absent minutes — over
`2024-01-01 → 2026-01-01` there are 11,573 gaps, of which **11,055 are 2–6 minute intraday
holes** (8,387 of them exactly 2 minutes). Each gets a `GapSignature` of
`(weekday, previous minute-of-day, next minute-of-day, duration)` that occurs once or twice
ever, so it falls under the 10-occurrence threshold and is marked unknown. Because
`boundaryAfter` returns the *ceiling* entry, nearly every bar's nearest boundary is one of
these one-off micro-holes, and the extractor drops the observation as `UNKNOWN_SESSION`.

Measured, not inferred: only **449 of 11,573 gaps (3.9%)** have a signature recurring ≥10
times over the full two years. The genuine daily boundary — the 61–62 minute gaps, ~283 of
them — is drowned out.

Both runs confirm it, so this is not a small-window artifact:

| Run | Bars | Observations kept | `UNKNOWN_SESSION` | Zones | Rules |
| --- | --- | --- | --- | --- | --- |
| 1 month (2024-01) | 5,307 | 0 | 10,166 of 10,614 | 0 | 0 |
| 24 months (full) | 132,792 | 8,266 (3.1%) | 256,870 (96.9%) | 0 | 0 |

The full run took **130 s** and completed with exit 0 under `-Xmx4g` with no memory pressure,
which settles the spec's runtime risk — the pipeline scales fine; the output is empty for the
session-calendar reason above, not a resource one. `dashboard/discovery-report.json` is
written and well-formed with correct provenance, but carries zero patterns, zero monthly
results and zero examples. Note the surviving 8,266 observations still yielded **zero
opportunity zones**, so whether fixing the calendar is sufficient on its own is unverified.

Requiring a minimum gap length before a boundary counts fixes it. Measured over the same
window: at ≥20 minutes, 438 gaps collapse to 84 signatures with **332 (76%) qualifying**,
versus 3.9% today. This is a change to discovery *analysis*, deliberately outside the Phase 1
spec's scope, so it was left unmade — but no discovery run can produce a non-empty report
until it lands.

**Deleting `experiments/discovery.sqlite` silently resets the one-shot OOS protection.**
`window_spent` lives in that file, so removing it to "start clean" hands back a budget that
is supposed to be unrecoverable. Pre-existing hazard, not introduced by this design — but
Section 3 puts the hypothesis ledger in the same file, which raises the cost of losing it.

**One-shot OOS protection already exists — do not rebuild it.** `DiscoveryStore` has a
`window_spent` table that raises `Discovery window is already spent` on a repeat insert;
`FinalValidationService` owns the protected window itself and only accepts an
already-persisted frozen candidate; `DiscoveryWindowPolicy.requireDevelopmentWindow`
rejects any overlap with 2026-01-01 → 2026-05-02. An earlier version of the approach
comparison wrongly claimed budget enforcement still needed building.

## Open questions

- ~~**Does the clean dataset get promoted into `main` before Phase 1?**~~ **Settled
  2026-08-08:** the question rested on the misreading retracted above. The owner directed the
  work into `.worktrees/delta-price-import`, which already holds the 917,650-row dataset, and
  `main` was merged in so the handover travels with it. No promotion is needed, and none
  would help — the database is gitignored and does not move between checkouts via git.
- ~~**Is the missing `public static void main` a deliberate Java 21 choice or a
  regression?**~~ **Answered 2026-08-08: regression.** `git log -L 21,21:…` shows the
  signature was `public static void main` from the first commit (`8613142`) until `b1c26ee`
  ("Introduce Capital.com API and WebSocket integration…") silently dropped `public`. Nothing
  in that commit concerns main-method style. Restoring `public` is a one-word fix, and Phase 1
  needs it.
- ~~**Section 2 approval**~~ **Granted 2026-08-08**, with all three open choices resolved as
  recommended: enforced tail reserve, logical content digest, fail-on-source-dirt. Terms are
  recorded under "Where this stands" above.
- **Which model tier runs the agent** — the ladder in `~/.claude/CLAUDE.md` puts design
  judgement at opus and hardest reasoning at fable. Not yet discussed.

## Files that matter

- `docs/superpowers/specs/2026-07-27-empirical-path-first-strategy-discovery-design.md` —
  the approved design the merged discovery pipeline implements; overlaps the charter's §6
  and §7 heavily.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/` — 46 files, the machinery the
  agent will drive. `ShallowRuleMiner`, `ExitPolicyGenerator`, `OpportunityZoneBuilder`,
  `ForwardPathLabeller`, `PromotionGate`, `FinalOosGate`, `DiscoveryStore`.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryRequest.java` — the
  eleven-field input Phase 1 must populate; defines both default artifact paths.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryWindowPolicy.java` — the
  protected OOS window constants that constrain every window choice.
- `src/main/java/io/g3tech/axetrader/config/AxeTraderMode.java` — gains a `DISCOVERY` value
  in Phase 1.
- `src/main/java/io/g3tech/axetrader/history/HistoryImportRunner.java` — the
  `CommandLineRunner` pattern Phase 1's runner should follow.
- `src/main/java/io/g3tech/axetrader/AxeTraderApplication.java` — line 21, the
  package-private `main` behind the build failure.
- `TODO.md` — accurate on the dataset (including the deliberately-legacy `.gz`); stale on
  discovery status, which it still lists as in-progress. Needs correcting there only.
- `docs/local-price-history.md` — the import/top-up runbook, and the authority on how the
  clean dataset is produced and refreshed in a checkout.
- `docs/d1-price-history-import-progress.md` — untracked in `main`, and describes the retired
  D1 workflow that `TODO.md` supersedes.
