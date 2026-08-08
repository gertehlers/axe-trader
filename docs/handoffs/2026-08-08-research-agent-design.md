---
date: 2026-08-08
status: open
branch: feature/delta-price-import
head: f30d903
next: Decide how to raise RUN-class yield — 17 of 35,826 observations clear the 0.8 composite cutoff, giving 8 zones per direction against MINIMUM_ZONES_PER_LEAF=10
---

# Axe-Trader Research Agent — Phase 1 built and running

A brainstorming session (`/superpowers:brainstorming`) turning a "Head Quantitative
Researcher" charter into a durable research agent. Four decisions locked, design Sections 1–3
approved, and **Phase 1 spec'd, implemented and run against the real two-year dataset**.

The discovery pipeline now works end to end for the first time. It still emits **zero candidate
rules**, but for a statistical reason rather than a defect — see "Where this stands".

> **Work happens in `.worktrees/delta-price-import`, not `main`.** The clean dataset is
> gitignored and exists per checkout. See the first landmine before moving anywhere else.

## Where this stands

### Locked by the owner

1. **Build it, don't be it** — a durable, reusable research agent, not a one-off analysis.
2. **Full charter**, all seven responsibilities, with one added constraint: numeric claims must
   come from the backtest engine, never the agent's own arithmetic.
3. **Persistent hypothesis ledger**, so falsified ideas are not re-proposed.
4. **Agent triggers its own runs** via a CLI entry point.

Approach: **"B with a real run first"** — build the entry point, produce a real report, then
design the ledger against observed output rather than guesses.

### Section 1 — approved

Architecture and the three-phase split, including `DISCOVERY` as a fourth `AxeTraderMode`
value rather than a separate CLI concern.

### Section 2 — approved, and built

The `DISCOVERY` entry point. Trigger is `--axe-trader.mode=discovery` with a
`DiscoveryRunner implements ApplicationRunner, ExitCodeGenerator` gated by
`@ConditionalOnProperty`, modelled on `HistoryImportRunner`. Development window
`2024-01-01 → 2026-01-01`, with `DiscoveryWindowPolicy` extended so everything from
`2026-05-02` onward is an enforced reserve. `inputDataHash` is a logical content digest, not a
SQLite file hash. A dirty `src/` or `application.yaml` refuses the run; `output/` is ignored.
All refusals happen before the pipeline starts, exit 1 via `ExitCodeGenerator`.

### Section 3 — approved, not built

The hypothesis ledger. Candidate identity already exists as `CandidateRule`'s content-addressed
sha256; the ledger adds a coarser **hypothesis key** over `(direction, sorted [(feature,
operator)])` with thresholds excluded — mandatory, because `ShallowRuleMiner` derives thresholds
from the data, so a shifted window re-mints the same idea at a different hash and exact dedup
would wave through unlimited near-misses of a falsified idea. Lifecycle
`OPEN → SUPPORTED | FALSIFIED` plus `PROMOTED` and `SUPERSEDED`, every transition citing a
`runKey`, a candidate id and a statistic. Falsified hypotheses are suppressed from proposals but
recorded as sightings. Tables live in `experiments/discovery.sqlite` so a transition and its
evidence commit together.

### Phase 1 — built, and the pipeline now runs

Spec: `docs/superpowers/specs/2026-08-08-discovery-entry-point-design.md`. Implementation was
test-first throughout, 233 → 304 tests.

Six defects were found and fixed by actually running it. All six were invisible to the existing
tests, which used synthetic contiguous bars:

| Defect | Fix |
| --- | --- |
| `./mvnw clean package` failed — `main` was package-private | `0aeb81f` |
| Run hung forever; servlet threads outlive the runner | `8de4940` (`OfflineMode`) |
| Session calendar treated every missing minute as a boundary | `330eb9b` |
| A bucket missing one minute was dropped, holing the series | `8cf693e` |
| 224-bar warm-up demanded perfect spacing | `8cf693e` |
| Session close looked up by exact match on a 5m grid | `9cd50b1` |
| `ForwardPathLabeller` hardcoded 5-minute bars | `f30d903` |

**Progress across the three full runs**, all measured:

| | First run | After calendar fix | After all fixes |
| --- | --- | --- | --- |
| Bars | 132,792 | 132,792 | 139,539 |
| Observations | 8,266 | 9,156 | **35,826** |
| Usable labels | 0 | 0 | **27,400** |
| `INCOMPLETE_GAP` | 8,266 (100%) | 9,156 (100%) | 8,426 (24%) |
| Opportunity zones | 0 | 0 | **16** |
| Candidate rules | 0 | 0 | 0 |
| Runtime | 130 s | 211 s | **2,748 s** |

Labels now break down as 19,692 `TRADING_CLOSE`, 7,708 `COMPLETE_48_BARS`, 8,426
`INCOMPLETE_GAP`. `dashboard/discovery-report.json` carries 4 examples and 0 patterns.

**Why rules are still zero — this is a guard, not a bug.**
`ShallowRuleMiner.MINIMUM_ZONES_PER_LEAF = 10`, and there are 8 zones per direction (16 total,
LONG 8 / SHORT 8). No leaf can reach the independence threshold, so the false-discovery control
correctly refuses to emit anything. Upstream of that, only **17 of 35,826** observations are
classed `RUN` — `OpportunityScorerV1` needs a composite ≥ 0.8, the mean of five percentile
ranks. The remaining gap is statistical power, not plumbing.

### Not started

Section 4 (the agent definition, its run loop, error handling, testing), its spec, and the
implementation plan. The ledger from Section 3 is designed but unbuilt.

## Next action

Decide how to raise `RUN`-class yield, because nothing downstream can proceed without it. Two
levers, and they need the owner because both change what discovery computes:

- **The `RUN` cutoff.** `OpportunityScorerV1.RUN_CUTOFF = 0.8` over a mean of five percentile
  ranks. Note `netMfe` (higher better) and `twoSidedExcursion` = mfe+|mae| (higher worse) are
  positively correlated in raw value, so their ranks are anti-correlated and the composite is
  structurally pulled toward the middle. Whether 0.8 is reachable in principle is worth
  checking before tuning it.
- **`MINIMUM_ZONES_PER_LEAF = 10`** against 8 zones per direction. Lowering it weakens the
  false-discovery control that exists to stop the miner inventing patterns.

Then Section 4, and the brainstorming flow: finish sections → spec → commit → self-review →
owner review → `writing-plans`.

## Verify current state

Run from `.worktrees/delta-price-import`. Observed 2026-08-08 at `f30d903`:

```
./mvnw test
# exit 0 — Tests run: 304, Failures: 0, Errors: 0, Skipped: 3
```

```
./mvnw clean package -DskipTests
# exit 0 — repackaged jar, Start-Class io.g3tech.axetrader.AxeTraderApplication
```

```
sqlite3 data/axe-trader.sqlite "SELECT COUNT(*), MIN(snapshot_time_utc), MAX(snapshot_time_utc) FROM historical_price;"
# 917650|2024-01-01T23:01:00Z|2026-08-06T19:55:00Z
```

The full discovery run, from a **clean tree** — the dirty-source gate refuses otherwise:

```
java -Xmx6g -jar target/axe-trader-0.0.1-SNAPSHOT.jar --axe-trader.mode=discovery
# exit 0 after 2748 s — "Discovery complete: 0 pattern(s) reported"
```

No lint command is documented for this project and none was run.

## Landmines

**The clean dataset is gitignored and lives per working checkout.** `data/*.sqlite` is excluded
by `.gitignore:6`; only `data/axe-trader.sqlite.gz` is tracked, and `TODO.md` states outright
that the committed snapshot is deliberately still the legacy one. So finding a 500,000-row
database in another checkout is **not** evidence that anything failed — it means that checkout
never ran the import. This worktree holds 917,650 rows over `2024-01-01 → 2026-08-06`; `main`
and `.worktrees/reusable-history-reingestion` hold the legacy 500,000 ending 2026-05-01.
Switching checkouts silently swaps a two-year dataset for a thirteen-month one, and only
`HistoryCursorReader` fails closed on it — the backtest and discovery paths just run on less
data. *An earlier version of this document reported the promotion as never having happened and
`TODO.md` as stale about it. Both claims were wrong.*

**A full discovery run now takes 46 minutes**, up from 130 s, because the labeller finally walks
real forward paths and `ShallowRuleMiner` does real work. It peaked around 3.4 GB against
`-Xmx6g`. Budget for that before iterating, and do not assume a run that has been quiet for
twenty minutes has hung — check `ps -p <pid> -o %cpu=,rss=`, not `ps -e -p <pid>`, which
silently ignores `-p` and reports an unrelated process.

**`./mvnw test` dirties the working tree** — it rewrites tracked `output/charts/chart.html` and
`output/charts/runner-results.html`. This blocks a discovery run, because the dirty-source gate
refuses when `src/` or `application.yaml` differ; `output/` is deliberately excluded, but
`git restore output/charts/` is still needed to keep the tree clean for commits.

**Deleting `experiments/discovery.sqlite` resets the one-shot OOS protection.** `window_spent`
lives in that file, so removing it to "start clean" hands back a budget that is meant to be
unrecoverable. Section 3 puts the hypothesis ledger in the same file, raising the cost further.
The file is currently 504 MB.

**`AxeTraderMode` is inert outside discovery.** It is referenced only by `AxeTraderRunner`,
whose `run()`, `ping()` and `close()` are never called from anywhere in `src/main`. So
`./mvnw spring-boot:run` performs no backtest despite `CLAUDE.md` saying it does, and MONITOR
mode "not working end-to-end" in `TODO.md` is this same dormancy rather than a separate bug.
Phase 1 deliberately did not repair it.

**`main` still cannot build.** The `public static void main` fix is only on this branch.

**SHA-256 of a live SQLite file is not a stable content identifier** — opening a database
rewrites bytes, which is why the clean dataset hashes `138ef7b2…` against `TODO.md`'s recorded
`e19cbb90…` while being logically identical. Use `DiscoveryInputDigest` for content identity.

**`TODO.md` is stale on the discovery work.** It lists the empirical path-first discovery as
in-progress with Tasks 5–12 unstarted and a worktree that no longer exists. The work is merged;
46 files sit under `src/main/java/io/g3tech/axetrader/backtest/discovery/`.

## Open questions

- **How to raise `RUN`-class yield** — the `RUN_CUTOFF` and `MINIMUM_ZONES_PER_LEAF` levers
  above. This is the blocking decision; see "Next action".
- **Does the 46-minute runtime need addressing before iterating?** Tolerable once, painful as a
  loop. Not investigated — no profiling was done, so where the time goes is unverified.
- **Which model tier runs the agent.** The ladder in `~/.claude/CLAUDE.md` puts design judgement
  at opus and hardest reasoning at fable. Still undiscussed.
- **Does `feature/delta-price-import` merge to `main`?** It is 15 commits ahead and carries the
  build fix, the whole import subsystem and all of Phase 1. Merging would not move the database.

## Files that matter

- `docs/superpowers/specs/2026-08-08-discovery-entry-point-design.md` — the approved Phase 1
  spec this implements.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryRunService.java` — builds the
  request; holds the bucket-tolerance opt-in and every pre-flight refusal.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryConfiguration.java` — the only
  place discovery beans are wired; gated on the mode property.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityScorerV1.java` —
  `RUN_CUTOFF = 0.8`; the first lever in the next action.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/ShallowRuleMiner.java` —
  `MINIMUM_ZONES_PER_LEAF = 10`; the second lever.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/ForwardPathLabeller.java` — boundary
  resolution and timeframe derivation, both fixed today.
- `src/main/java/io/g3tech/axetrader/backtest/series/BarSeriesFactory.java` — wall-clock bucket
  aggregation and `maxMissingMinutesPerBucket`; strict by default so backtests are unchanged.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/ObservableStateExtractor.java` —
  `MAX_TOLERATED_MISSING_BARS`, and `requiredHistory` driven by `trend-ema-period: 200`.
- `src/main/java/io/g3tech/axetrader/backtest/discovery/session/HistoricalSessionCalendar.java`
  — `MINIMUM_SESSION_GAP = 20 minutes`.
- `src/main/java/io/g3tech/axetrader/OfflineMode.java` — which modes run headless.
- `TODO.md` — accurate on the dataset, stale on discovery status.
