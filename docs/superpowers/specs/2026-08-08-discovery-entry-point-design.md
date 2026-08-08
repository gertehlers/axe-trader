# Discovery Entry Point (Phase 1)

## Goal

Make the merged discovery pipeline runnable outside tests. One command produces one real
discovery report over the 2024-onward development window, with recorded provenance:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--axe-trader.mode=discovery"
```

Today `DiscoveryPipeline` is referenced only by `DiscoveryPipelineTest` and
`EmpiricalDiscoveryHarnessTest`. Neither default artifact has ever existed on disk — no
`experiments/discovery.sqlite`, no `dashboard/discovery-report.json` — so the JSON contract a
research agent is meant to read is currently produced only by test fixtures. This design closes
that gap and nothing else.

## Scope and boundaries

**In scope:** a `DISCOVERY` mode value, an `ApplicationRunner` that builds a `DiscoveryRequest`
and invokes the existing pipeline, window and provenance policy, and the failure behaviour
around all of it.

**Explicitly out of scope**, deferred until a real report exists to design against:

- The hypothesis ledger (approved as design Section 3, not built here).
- The research agent itself, its run loop, and its model tier.
- Any change to discovery *analysis* — no new features, no scorer or miner changes, no exit
  policy work. Phase 1 wires up what is already there and must not alter what it computes.
- Promotion or final OOS validation. `FinalValidationService` is not called by this path.

**Why Phase 1 alone.** The locked approach is "B with a real run first": produce observed
output, then design the ledger against it rather than against guesses. `DiscoveryReport` is
`Map<String, Object>` end to end, so the statistic field names an agent would key off are not
knowable from the source. One real run settles them.

## 1. Trigger and wiring

`AxeTraderMode` gains a fourth value, `DISCOVERY`. No change to `AxeTraderMode.from` — the
existing `trim().replace('-','_').toUpperCase()` already parses `discovery` — and no change to
`isLiveMarketMode()`, which correctly returns false so the live market path stays off.

A new `DiscoveryRunner implements ApplicationRunner, ExitCodeGenerator`, gated by
`@ConditionalOnProperty(prefix = "axe-trader", name = "mode", havingValue = "discovery")`,
modelled directly on `HistoryImportRunner`. Gating on the property rather than branching inside
the runner keeps the bean absent entirely during `BACKTEST` and `MONITOR`.

**Note on the surrounding state.** `AxeTraderMode` is currently inert: it is referenced only by
`AxeTraderRunner`, whose `run()`, `ping()` and `close()` are never called from `src/main`.
Adding an enum constant therefore accomplishes nothing on its own — the runner *is* the feature.
Phase 1 deliberately does not repair `AxeTraderRunner`; MONITOR-mode dormancy is a separate
concern with its own entry in `TODO.md`.

`DiscoveryPipeline` is not a Spring bean and constructs its own collaborators. Phase 1 supplies
it via an `@Bean` method rather than annotating the class, so discovery wiring stays out of the
pipeline. Its three constructor dependencies are `ObservableStateExtractor` (over the existing
`EntryFeatureExtractor` `@Component`), `ForwardPathLabeller`, and the existing `StrategyFactory`
`@Component`.

## 2. Window resolution

Configuration, under a new `axe-trader.discovery` prefix for consistency with
`axe-trader.history-import`:

```yaml
axe-trader:
  discovery:
    from: 2024-01-01T00:00:00Z
    to: 2026-01-01T00:00:00Z
```

Instrument, timeframe and strategy come from the existing `backtest.*` block — `backtest.epic`,
`backtest.timeframe-minutes`, `backtest.strategy` — so a discovery run and a backtest describe
the same instrument by construction.

`backtest.limit` is deliberately **not** used. Discovery is window-bounded, not row-bounded; a
row cap would silently truncate the window and make the recorded `window_from`/`window_to`
provenance a lie.

### 2.1 Extending `DiscoveryWindowPolicy`

The current guard rejects only overlap with the protected band:

```java
if (from.isBefore(OOS_TO) && OOS_FROM.isBefore(to)) throw ...
```

A window lying entirely *after* `2026-05-02` passes. The ~3 months the delta import added out to
`2026-08-06` are therefore unprotected, and a discovery run could consume the only data no
strategy has ever seen.

Add a reserve rule: **a development window must end at or before `OOS_FROM` (2026-01-01).**

Order matters. The existing band check runs first and keeps its existing message, so
`2025-12-31 → 2026-01-02` still fails with "Development window overlaps protected OOS period"
and the existing test is untouched. The reserve check runs second with its own message and
catches tail windows the band check waves through.

Verified compatible with existing callers: `FinalValidationService` uses only the `OOS_FROM` /
`OOS_TO` constants and never calls `requireDevelopmentWindow`;
`EmpiricalDiscoveryHarnessTest`'s `DEFAULT_TO` is exactly `OOS_FROM`, which the new rule admits;
`DiscoveryPipelineTest`'s synthetic series sits in June 2025.

## 3. Series construction

Load the window with
`HistoricalPriceRepository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc` — the
existing derived query is exactly the shape needed — then
`BarSeriesFactory.fromPricesWithSides(epic, prices, timeframeMinutes)` for the side-aware
`MarketSeries` the pipeline requires.

`sessionCalendar` comes from `HistoricalSessionCalendar.fit(oneMinuteBarTimes, minimumOccurrences)`
over the same window's 1-minute timestamps, so the calendar is fitted to the data actually being
analysed rather than assumed.

## 4. Provenance

`sourceCommit` from `git rev-parse HEAD`.

`inputDataHash` is a **logical content digest**, not a hash of the SQLite file: row count,
min and max `snapshot_time_utc`, and a SHA-256 over the ordered `(snapshot_time_utc, OHLC)`
tuples in the window. A file hash is not reproducible — merely opening a SQLite database mutates
its bytes, which is why the clean dataset now hashes `138ef7b2…` against a recorded `e19cbb90…`
while being logically identical. The digest is computed over the rows already loaded in §3, so it
costs no extra query.

### 4.1 Dirty-tree gate

A run records `sourceCommit`, so uncommitted changes make provenance ambiguous. Refuse to run
when `git status --porcelain` reports modifications under `src/` or to `application.yaml`.

Ignore `output/`: `./mvnw test` rewrites the tracked files `output/charts/chart.html` and
`output/charts/runner-results.html`, so a blanket check would block discovery on an otherwise
clean checkout immediately after running the suite.

**Accepted limitation.** `DiscoveryPipeline` hardcodes `sourceDirty = false` when it builds
`DiscoveryRun`, and `DiscoveryRequest` has no dirty field. Phase 1 does not widen either record.
Because the runner refuses to start when source is dirty, `false` is accurate for every run that
reaches the pipeline. Dirt confined to `output/` is not recorded — acceptable, since those files
cannot change what a run computes.

## 5. Failure handling

Fail **before** the pipeline starts, never midway, so a rejected run leaves no partial rows.
Exit code 1 via `ExitCodeGenerator`, matching `HistoryImportRunner.runUpdate()`. Refuse when:

| Condition | Reason |
| --- | --- |
| Window overlaps the protected band, or ends after `OOS_FROM` | §2.1 |
| Source tree dirty under `src/` or `application.yaml` | §4.1 |
| Window loads zero rows | Nothing to analyse; usually a wrong epic or an unimported window |
| Aggregated series too short for the configured indicator periods | Guarantees a useless run |

Do **not** re-implement one-shot protection. `DiscoveryStore.window_spent` already raises
`Discovery window is already spent` on a repeat insert, and `FinalValidationService` already owns
the protected window.

## 6. Artifacts

Both paths are left to `DiscoveryRequest`'s own defaults — `experiments/discovery.sqlite` and
`dashboard/discovery-report.json` — by passing null, so the record's compact constructor stays
the single source of truth. `DiscoveryPipeline` already exports the report itself at
`DiscoveryPipeline:166`; the runner does not write JSON.

`experiments/` must be gitignored but its parent directories created on demand. **The database
must not be deleted to "start clean": `window_spent` lives in it, and removing it hands back the
one-shot OOS budget.** Section 3 will add the hypothesis ledger to this same file, raising the
cost of losing it further.

## 7. Testing

- `AxeTraderMode.from("discovery")` resolves to `DISCOVERY`, and `isLiveMarketMode()` is false
  for it.
- `DiscoveryWindowPolicy` rejects a tail window (`2026-06-01 → 2026-07-01`), still rejects a
  band-overlapping window with the original message, and admits `2024-01-01 → 2026-01-01`.
- The logical digest is stable across two computations over equal row lists, and differs when a
  single OHLC value differs.
- The dirty-tree gate trips on a `src/` modification and ignores an `output/` modification.
- The runner refuses an empty window and a too-short series, each with exit code 1 and without
  creating a run row.
- One end-to-end run over a small window backed by a temporary database, asserting a report file
  is written and one `DiscoveryRun` row persisted. The full 24-month run is **not** a unit test.

## 8. Risks

**Runtime and memory are unmeasured at this scale.** The window holds roughly 700,000 1-minute
rows aggregating to ~140,000 5-minute bars, and the pipeline loops every bar performing
forward-path labelling, with observations accumulated in an in-memory `List`. Every existing test
runs against a few hundred synthetic bars. A first full run may be slow or may exhaust heap.

Mitigation: run a short window first (one month) to confirm the path end to end and get a
per-bar cost, then extrapolate before committing to the full 24 months. If memory is the binding
constraint, that is a finding about `DiscoveryPipeline` to be fixed on its own terms — not
something to paper over by shrinking the window, which would quietly weaken every statistic the
run produces.
