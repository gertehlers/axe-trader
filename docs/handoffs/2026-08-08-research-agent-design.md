---
date: 2026-08-08
status: open
branch: main
head: 49b6a09
next: Re-present design Section 2 with corrected dataset facts, then continue to Section 3 (hypothesis ledger)
---

# Axe-Trader Research Agent — design in progress

A brainstorming session (`/superpowers:brainstorming`) turning a "Head Quantitative
Researcher" charter into a durable research agent. **No code and no spec file have been
written yet.** The output so far is four locked decisions and two design sections, one of
which now needs revising because evidence gathered during this handover contradicts it.

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

**In flight — the design sections.** Section 1 (architecture and three-phase split) was
presented and approved, including the specific decision to add `DISCOVERY` as a fourth
`AxeTraderMode` value rather than a separate CLI concern. Section 2 (the `DISCOVERY` entry
point) was presented but **the owner invoked `/handover` instead of answering, so Section 2
is NOT approved.**

**Not started:** Sections 3 onward (ledger data model and lifecycle, the agent definition
and its run loop, error handling, testing), the spec document, the implementation plan.

**Section 2 is now known to be partly wrong.** It asserted the dataset spans 2024-01-01 to
2026-08-02 and proposed a two-year development window of 2024-01-01 → 2026-01-01, holding
2026-05-02 → 2026-08-02 in reserve. That is true of the dataset in two *worktrees*, not of
the one in `main`. See the first landmine. The corrected picture:

| Location | Rows | Range |
| --- | --- | --- |
| `main` working tree and committed `.gz` | 500,000 | 2024-12-04T23:20Z → 2026-05-01T08:25Z |
| `.worktrees/clean-local-price-history` | 912,132 | 2024-01-01T23:01Z → 2026-08-02T22:52Z |
| `.worktrees/delta-price-import` | 917,650 | 2024-01-01T23:01Z → 2026-08-06T19:55Z |

Against `main`'s actual data, `DiscoveryWindowPolicy`'s protected window (2026-01-01 →
2026-05-02) leaves only ~13 months of development data, and the reserve quarter does not
exist at all — the data stops one day before the protected window ends.

## Next action

Re-present design Section 2 to the owner with the corrected dataset facts above, stating
plainly that the two-year window and the reserve quarter both depend on promoting a clean
dataset into `main` first. Then get approval and continue to Section 3 (hypothesis ledger
data model and lifecycle). The brainstorming skill's flow is: finish sections → write spec
to `docs/superpowers/specs/2026-08-08-<topic>-design.md` → commit → self-review → owner
reviews → invoke `writing-plans`. Do not write implementation code before the owner
approves the spec.

## Verify current state

Observed just now, at `49b6a09` on `main`:

```
./mvnw test
# exit 0 — Tests run: 233, Failures: 0, Errors: 0, Skipped: 3
```

```
./mvnw clean package -DskipTests
# exit 1 — Failed to execute goal spring-boot-maven-plugin:4.0.5:repackage
#          Unable to find main class
```

Dataset in `main`, observed via `sqlite3`:

```
sqlite3 data/axe-trader.sqlite "SELECT COUNT(*), MIN(snapshot_time_utc), MAX(snapshot_time_utc) FROM historical_price;"
# 500000|2024-12-04T23:20Z|2026-05-01T08:25Z
```

No lint command is documented for this project and none was run.

## Landmines

**The clean price dataset was never promoted into `main`.** `TODO.md` states the promotion
"succeeded" on 2026-08-03 with 912,132 rows and SHA-256 `e19cbb90…`. In `main`'s working
tree, `data/axe-trader.sqlite` holds 500,000 rows over a shorter range and hashes to
`debe29b4…`; the committed `data/axe-trader.sqlite.gz` decompresses to that same
`debe29b4…`, so the archive `DatabaseBootstrap` restores from is the old dataset too. The
clean data exists only inside the worktrees listed above. `main`'s database also lacks the
`price_exclusion` table and the six `history_import_*` tables that both worktree databases
have. Anything that assumes `TODO.md`'s dataset while working in `main` will be wrong.

**`./mvnw clean package -DskipTests`, the build command documented in `CLAUDE.md`, fails on
`main`.** `AxeTraderApplication.java:21` declares `static void main(String[] args)` —
package-private, not `public static void main` — and `grep -rn "public static void main"
src/main/java` returns nothing. Spring Boot's `repackage` goal cannot find an entry point,
so no runnable jar is produced. This directly blocks the planned Phase 1, which needs the
application to start in a new `DISCOVERY` mode. Whether `./mvnw spring-boot:run` still
works was not tested; do not assume either way.

**`./mvnw test` dirties the working tree.** It rewrites the tracked file
`output/charts/runner-results.html`. This matters more than it looks: Section 2 proposes
that a discovery run record `sourceCommit` and fail closed on a dirty tree, and running the
test suite would trip that check on an otherwise clean checkout.

**SHA-256 of a live SQLite file is not a stable content identifier.** The clean dataset in
`.worktrees/clean-local-price-history` matches `TODO.md`'s row count and time range exactly
(912,132 rows, 2024-01-01T23:01Z → 2026-08-02T22:52Z) but now hashes to `138ef7b2…` rather
than the recorded `e19cbb90…`. Opening a SQLite database can change its bytes. Section 2's
proposal to use the file hash as `DiscoveryRequest.inputDataHash` should be reconsidered in
favour of a digest over logical content.

**`TODO.md` is badly stale on the discovery work.** It describes the empirical path-first
discovery as active with Task 3 in progress, Tasks 5–12 unstarted, and four uncommitted
tests to preserve in a worktree at `.worktrees/empirical-path-first-discovery`. In fact the
work is complete and merged to `main` — 46 files under
`src/main/java/io/g3tech/axetrader/backtest/discovery/`, with `94d9759 feat(discovery): add
task 10 and 11 dashboard integration` as the most recent commit touching it. That worktree
no longer exists; `git worktree list` shows only three, and the branch survives as
`remotes/origin/feature/empirical-path-first-discovery`.

**The discovery pipeline has never run outside tests.** `DiscoveryPipeline` is referenced
only by `DiscoveryPipelineTest` and `EmpiricalDiscoveryHarnessTest`. There is no
`CommandLineRunner` for it, no `DISCOVERY` value in `AxeTraderMode`, and neither default
artifact exists on disk — no `experiments/discovery.sqlite`, no
`dashboard/discovery-report.json`. The JSON contract the agent is meant to read is
currently produced only by test fixtures.

**One-shot OOS protection already exists — do not rebuild it.** `DiscoveryStore` has a
`window_spent` table that raises `Discovery window is already spent` on a repeat insert;
`FinalValidationService` owns the protected window itself and only accepts an
already-persisted frozen candidate; `DiscoveryWindowPolicy.requireDevelopmentWindow`
rejects any overlap with 2026-01-01 → 2026-05-02. An earlier version of the approach
comparison wrongly claimed budget enforcement still needed building.

## Open questions

- **Does the clean dataset get promoted into `main` before Phase 1, and from which
  worktree?** `delta-price-import` has 5,518 more rows and four extra days than
  `clean-local-price-history`. Its branch `feature/delta-price-import` (at `08dbfb4`) is not
  merged. Phase 1's window decision depends entirely on this and cannot be settled without
  the owner.
- **Is the missing `public static void main` a deliberate Java 21 flexible-main-methods
  choice or a regression?** It changes whether fixing it is a one-word edit or a discussion.
- **Section 2 approval**, once re-presented with corrected facts.
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
- `TODO.md` — stale on discovery status and on the dataset promotion; needs correcting.
- `docs/d1-price-history-import-progress.md` — untracked, and describes the retired D1
  workflow that `TODO.md` supersedes.
