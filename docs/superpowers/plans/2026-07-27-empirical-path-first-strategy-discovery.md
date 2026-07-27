# Empirical Path-First Strategy Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a leak-safe, every-bar discovery pipeline that finds explainable long and short
entry patterns, derives pattern-specific exits, and validates frozen candidates by net monthly
performance without reading the protected final OOS window.

**Architecture:** Java produces side-aware market series, backward-only observations, forward path
labels, empirical opportunity zones, compact rules, exit policies, and executable validation.
Discovery artifacts live in a separate local SQLite store; only compact audit reports are exported
to the existing Cloudflare dashboard. Observable state and future labels use separate types, tables,
and APIs.

**Tech Stack:** Java 21, Spring Boot 4, ta4j 0.22.6, SQLite JDBC, Jackson, JUnit 5 + AssertJ,
Maven; TypeScript 5, Hono, React 19, Vitest, Cloudflare D1.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-27-empirical-path-first-strategy-discovery-design.md`.
  Read it before every task.
- Evaluate every eligible completed five-minute bar as both `LONG` and `SHORT`.
- Discovery observations may overlap. Executable validation permits one open position per
  instrument.
- Signal on a completed bar; fill on the next executable bar.
- Use side-aware bid/ask prices in discovery. Do not also subtract the legacy average spread.
- Maximum hold is 48 five-minute bars; broker trading close ends the path earlier.
- Unknown session boundaries and unexplained data gaps fail closed.
- Feature extraction must be backward-only. Future data may appear only in `ForwardPathLabel`.
- Entry rules contain at most four clauses. Invalidation rules contain at most two.
- Tree depth is at most three; no more than ten entry families per direction advance.
- Exit policy templates and the twelve-policy-per-pattern cap are copied exactly from the spec.
- Discovery gate: positive total net and at least 50% profitable sampled months.
- Promotion gate: positive total net, positive median month, at least 70% profitable sampled
  months, six sampled months, ten trades per sampled month, net/max-drawdown at least 1.0.
- `2026-01-01T00:00:00Z` through `2026-05-02T00:00:00Z` is protected. Normal discovery commands
  must reject any intersecting window.
- Do not run final OOS validation while implementing this plan.
- Existing `BacktestRunnerIntrabarTest` and `BacktestRunnerTieredExitTest` tests must remain unchanged.
- Run Java tests with `./mvnw test -Dtest=<TestClass>`. Run the full suite with `./mvnw test`.
- Run dashboard checks from `dashboard/` with `npm test`, `npm run typecheck`, and `npm run build`.
- Commit after every task and push after every commit. Log the first real discovery run in
  `TODO.md`; do not log fabricated results.

---

### Task 1: Side-aware market series

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/series/MarketSeries.java`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/series/BarSeriesFactory.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/series/MarketSeriesTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/series/BarSeriesFactoryTest.java`

**Interfaces:**
- Consumes: ascending `List<HistoricalPrice>`.
- Produces:
  - `record MarketSeries(BarSeries mid, BarSeries bid, BarSeries ask)`
  - `double entryPrice(Direction direction, int index)`
  - `Bar exitBar(Direction direction, int index)`
  - `MarketSeries BarSeriesFactory.fromPricesWithSides(
    String epic, List<HistoricalPrice> prices, int timeframeMinutes)`

- [ ] **Step 1: Write failing direction-side tests**

Add tests that construct one bid bar closing at `99.0`, one ask bar closing at `101.0`, and assert:

```java
assertThat(series.entryPrice(Direction.LONG, 0)).isEqualTo(101.0);
assertThat(series.entryPrice(Direction.SHORT, 0)).isEqualTo(99.0);
assertThat(series.exitBar(Direction.LONG, 0).getClosePrice().doubleValue()).isEqualTo(99.0);
assertThat(series.exitBar(Direction.SHORT, 0).getClosePrice().doubleValue()).isEqualTo(101.0);
```

Add a factory test with two one-minute `HistoricalPrice` rows and assert the mid, bid, and ask
series have identical timestamps and distinct expected closes. Add rejection tests for non-positive
prices and `ask < bid`; discovery must never silently turn missing side data into a free spread.

- [ ] **Step 2: Run the tests and confirm the failure**

Run:

```bash
./mvnw test -Dtest=MarketSeriesTest,BarSeriesFactoryTest
```

Expected: compilation fails because `MarketSeries` and `fromPricesWithSides` do not exist.

- [ ] **Step 3: Add the immutable sided-series boundary**

Create:

```java
package io.g3tech.axetrader.backtest.series;

import io.g3tech.axetrader.backtest.runner.Direction;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

public record MarketSeries(BarSeries mid, BarSeries bid, BarSeries ask) {
    public MarketSeries {
        if (mid.getBarCount() != bid.getBarCount() || mid.getBarCount() != ask.getBarCount()) {
            throw new IllegalArgumentException("mid, bid and ask series must have equal bar counts");
        }
    }

    public double entryPrice(Direction direction, int index) {
        Bar bar = direction == Direction.LONG ? ask.getBar(index) : bid.getBar(index);
        return bar.getClosePrice().doubleValue();
    }

    public Bar exitBar(Direction direction, int index) {
        return (direction == Direction.LONG ? bid : ask).getBar(index);
    }
}
```

- [ ] **Step 4: Add side-preserving aggregation**

Refactor `BarSeriesFactory.fromPrices` to delegate to
`fromPricesWithSides(epic, prices, timeframeMinutes).mid()`. Add a private
builder that maps the four OHLC fields for `MID`, `BID`, and `ASK`, aggregates each one-minute
series using the existing `DurationBarAggregator`, and verifies matching end times at every index.
Validate every source OHLC side is positive and every ask field is greater than or equal to its bid.
Do not change the public behaviour of
`BarSeries build(String epic, int limit, int timeframeMinutes)`.

- [ ] **Step 5: Verify focused and regression tests**

Run:

```bash
./mvnw test -Dtest=MarketSeriesTest,BarSeriesFactoryTest,BacktestRunnerIntrabarTest
```

Expected: all pass; existing midpoint backtests remain unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/series/MarketSeries.java \
  src/main/java/io/g3tech/axetrader/backtest/series/BarSeriesFactory.java \
  src/test/java/io/g3tech/axetrader/backtest/series/MarketSeriesTest.java \
  src/test/java/io/g3tech/axetrader/backtest/series/BarSeriesFactoryTest.java
git commit -m "feat(discovery): add side-aware market series"
git push
```

---

### Task 2: Trading-session and protected-window boundaries

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/session/SessionBoundary.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/session/TradingSessionCalendar.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/session/HistoricalSessionCalendar.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/session/UnknownSessionBoundaryException.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryWindowPolicy.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/session/HistoricalSessionCalendarTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/DiscoveryWindowPolicyTest.java`

**Interfaces:**
- Produces:
  - `record SessionBoundary(Instant finalExecutableBar, Instant nextOpen)`
  - `Optional<SessionBoundary> TradingSessionCalendar.boundaryAfter(Instant barTime)`
  - `int TradingSessionCalendar.minutesToClose(Instant barTime)`
  - `void DiscoveryWindowPolicy.requireDevelopmentWindow(Instant from, Instant to)`

- [ ] **Step 1: Write failing session tests**

Use a fixture with ten recurring daily gaps from `21:59Z` to `23:00Z`, plus one isolated
`18:26Z` to `19:04Z` gap. Assert:

```java
assertThat(calendar.minutesToClose(Instant.parse("2025-02-03T20:59:00Z"))).isEqualTo(60);
assertThat(calendar.boundaryAfter(Instant.parse("2025-02-03T20:59:00Z")))
        .get().extracting(SessionBoundary::finalExecutableBar)
        .isEqualTo(Instant.parse("2025-02-03T21:59:00Z"));
assertThat(calendar.boundaryAfter(Instant.parse("2025-02-04T18:00:00Z"))).isEmpty();
```

The isolated gap must remain unknown rather than becoming a session close.

- [ ] **Step 2: Write failing protected-window tests**

```java
assertThatCode(() -> policy.requireDevelopmentWindow(
        Instant.parse("2025-01-01T00:00:00Z"),
        Instant.parse("2025-12-31T23:59:59Z"))).doesNotThrowAnyException();

assertThatThrownBy(() -> policy.requireDevelopmentWindow(
        Instant.parse("2025-12-01T00:00:00Z"),
        Instant.parse("2026-02-01T00:00:00Z")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("protected OOS");
```

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=HistoricalSessionCalendarTest,DiscoveryWindowPolicyTest
```

Expected: compilation fails for the missing types.

- [ ] **Step 4: Implement recurring historical session recognition**

`HistoricalSessionCalendar.fit(List<Instant> oneMinuteBarTimes, int minimumOccurrences)` groups gaps
by weekday, previous-bar UTC minute-of-day, next-bar UTC minute-of-day, and gap duration rounded to
the nearest minute. Only groups with at least ten occurrences are known session boundaries.
Weekend gaps and the recurring one-hour daily maintenance gap qualify; isolated data gaps do not.
`minutesToClose` uses only a fitted known boundary and throws `UnknownSessionBoundaryException`
otherwise.

- [ ] **Step 5: Implement the immutable OOS guard**

Use constants:

```java
public static final Instant OOS_FROM = Instant.parse("2026-01-01T00:00:00Z");
public static final Instant OOS_TO = Instant.parse("2026-05-02T00:00:00Z");
```

Reject half-open windows `[from,to)` where `from.isBefore(OOS_TO) && OOS_FROM.isBefore(to)`.

- [ ] **Step 6: Verify**

Run:

```bash
./mvnw test -Dtest=HistoricalSessionCalendarTest,DiscoveryWindowPolicyTest
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery \
  src/test/java/io/g3tech/axetrader/backtest/discovery
git commit -m "feat(discovery): guard sessions and protected windows"
git push
```

---

### Task 3: Backward-only observable state

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/ObservationId.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/FeatureVector.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/ObservableState.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/ObservationExclusion.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/ObservationBatch.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/ObservableStateExtractor.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/runner/EntryFeatureExtractor.java`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/ObservableStateExtractorTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/runner/EntryFeatureExtractorTest.java`

**Interfaces:**
- Produces:

```java
public record ObservationId(
        String instrument, int timeframeMinutes, Instant signalTime, Direction direction) {}

public record ObservableState(
        ObservationId id, int signalIndex, int entryIndex, double entryAtr,
        int minutesToTradingClose, FeatureVector features) {}
```

- `FeatureVector` is an immutable, lexicographically ordered `Map<String, Double>` with
  `double required(String name)` and deterministic Jackson JSON.
- `ObservationExclusion.Reason` values are `INDICATOR_WARMUP`, `UNKNOWN_SESSION`,
  `NO_EXECUTABLE_NEXT_BAR`, and `NON_FINITE_FEATURE`.
- `ObservationBatch ObservableStateExtractor.extract(
  String instrument, int timeframeMinutes, MarketSeries market, IndicatorBundle indicators,
  ConfluenceStrategies strategies, BacktestProperties.Strategy config,
  TradingSessionCalendar calendar, int signalIndex)` returns two states per eligible signal index
  or counted exclusions for that index.

- [ ] **Step 1: Pin current trade features before extraction**

Write a hand-built series test for `EntryFeatureExtractor.at(signalIndex)` that asserts RSI, ATR,
support/resistance distance, volume ratio, UTC hour, and confluence score. Add a regression test
that an existing `TradeResult.features()` equals the shared extractor result for the same signal bar.

- [ ] **Step 2: Write the every-bar and backward-only tests**

Build a 240-bar fixture, extract one signal index, then replace every bar after that index with
extreme values. Assert:

```java
assertThat(after.features()).isEqualTo(before.features());
assertThat(after.id()).isEqualTo(before.id());
```

Also assert both directions exist and lag keys include:

```java
assertThat(state.features().values()).containsKeys(
        "rsi", "rsi.delta.1", "rsi.delta.2", "rsi.delta.3",
        "rsi.delta.6", "rsi.delta.12",
        "pillar.rsi_bb.active", "pillar.rsi_bb.persistence.3",
        "pillar.volume_trend.activated", "minutes_to_trading_close");
```

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=EntryFeatureExtractorTest,ObservableStateExtractorTest
```

Expected: compilation fails for the new extractor and model types.

- [ ] **Step 4: Extract existing feature math without changing semantics**

Move `BacktestRunner.featuresAt` and `atrPercentile` into `EntryFeatureExtractor`. Its public method
takes the signal index directly:

```java
public TradeFeatures at(
        BarSeries series,
        IndicatorBundle indicators,
        BacktestProperties.Strategy config,
        int signalIndex,
        int confluenceScore)
```

Change `BacktestRunner` to call it with `entryIndex - 1`. Keep all current runner tests unchanged.

- [ ] **Step 5: Implement the rich observable vector**

For current values and lags `{1,2,3,6,12}`, emit finite numeric features for prices, returns, RSI,
BB/EMA/support/resistance distances, trend slope/acceleration, ATR/ATR percentile, candle anatomy,
volume ratio/change, pillar active/activated/deactivated/persistence, confluence, hour/day, and
minutes to close. Use `1.0/0.0` for booleans. Reject NaN and infinity in `FeatureVector`.

Evaluate existing `PillarVote.rule().isSatisfied(index)` for the boolean votes. Calculate raw pillar
distances from the same `IndicatorBundle` and config definitions used by `StrategyFactory`.
Return an `ObservationExclusion` instead of silently skipping a bar when warm-up, session, next-bar,
or finite-value validation fails.

- [ ] **Step 6: Verify focused and full runner regression tests**

Run:

```bash
./mvnw test -Dtest=EntryFeatureExtractorTest,ObservableStateExtractorTest,BacktestRunnerTest
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery \
  src/main/java/io/g3tech/axetrader/backtest/runner/EntryFeatureExtractor.java \
  src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java \
  src/test/java/io/g3tech/axetrader/backtest/discovery \
  src/test/java/io/g3tech/axetrader/backtest/runner/EntryFeatureExtractorTest.java
git commit -m "feat(discovery): extract backward-only every-bar state"
git push
```

---

### Task 4: Forward path labels and leakage barrier

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/LabelStatus.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/PathPoint.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/ForwardPathLabel.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/model/LabelledObservation.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/ForwardPathLabeller.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/ForwardPathLabellerTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/LeakageBoundaryTest.java`

**Interfaces:**
- `LabelStatus` values: `COMPLETE_48_BARS`, `TRADING_CLOSE`, `INCOMPLETE_GAP`,
  `NO_EXECUTABLE_NEXT_BAR`.
- `ForwardPathLabel` stores entry price/time, exit-side `PathPoint`s, MFE/MAE in points and ATR,
  MAE-before-MFE, event ordering, horizon returns, directional efficiency, and time-to-events.
- `record LabelledObservation(ObservableState state, ForwardPathLabel label)` is available only
  inside offline discovery/analysis packages.
- No method accepting `ObservableState` may return a type containing label fields.

- [ ] **Step 1: Write hand-calculated long and short path tests**

For a long entry at ask `101`, exit-side bid bars reaching `104`, dipping to `100`, and closing at
`103`, assert MFE `+3`, MAE `-1`, and the event ordering. Mirror the fixture for short using bid
entry and ask exits.

- [ ] **Step 2: Write horizon, close, gap, and leakage tests**

Assert:

- a 60-bar series produces exactly 48 labelled bars;
- a known close after 12 bars returns `TRADING_CLOSE`;
- an isolated unexplained gap returns `INCOMPLETE_GAP`;
- no next bar returns `NO_EXECUTABLE_NEXT_BAR`;
- mutating future bars changes the label but not the already-extracted `ObservableState`.

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=ForwardPathLabellerTest,LeakageBoundaryTest
```

Expected: compilation fails for the label types.

- [ ] **Step 4: Implement exact path arithmetic**

Enter at `state.entryIndex()` using `MarketSeries.entryPrice(direction,index)`. Walk
`MarketSeries.exitBar(direction,index)` through `min(entryIndex + 48, sessionFinalIndex)`.
For a long, favourable excursion uses exit-side high minus entry; adverse uses low minus entry.
For a short, favourable uses entry minus exit-side low; adverse uses entry minus exit-side high.
Store points and divide by `state.entryAtr()` for ATR units.

- [ ] **Step 5: Enforce incomplete-label exclusion**

Expose:

```java
public boolean eligibleForDiscovery() {
    return status == LabelStatus.COMPLETE_48_BARS || status == LabelStatus.TRADING_CLOSE;
}
```

Do not coerce gaps into session closes or end-of-data exits.

- [ ] **Step 6: Verify**

Run:

```bash
./mvnw test -Dtest=ForwardPathLabellerTest,LeakageBoundaryTest
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery \
  src/test/java/io/g3tech/axetrader/backtest/discovery
git commit -m "feat(discovery): label four-hour forward paths"
git push
```

---

### Task 5: Versioned discovery SQLite store

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/store/DiscoveryRun.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/store/DiscoveryStore.java`
- Create: `src/main/resources/discovery/V1__discovery_schema.sql`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/store/DiscoveryStoreTest.java`

**Interfaces:**
- `DiscoveryStore.open(Path)`
- `long beginRun(DiscoveryRun run)`
- `void saveObservation(long runId, ObservableState state)`
- `void saveExclusion(long runId, ObservationExclusion exclusion)`
- `void saveLabel(long runId, ObservationId id, ForwardPathLabel label)`
- `void appendWindowSpent(long runId, Instant from, Instant to, String candidateId)`
- read methods return observable and label records separately.
- `DiscoveryRun` contains `runKey`, input-data SHA-256, config SHA-256, feature-schema version,
  score version, source commit/dirty flag, instrument, timeframe, and half-open data window.

- [ ] **Step 1: Write failing schema and separation tests**

Using `@TempDir`, save one state and label. Assert the observation row contains `features_json` but
no `mfe` column, while `forward_label` contains MFE but no `features_json`. Assert saving the same
observation identity twice fails.

- [ ] **Step 2: Write deterministic identity tests**

Create two runs with the same data hash, config hash, feature schema, score version, source commit,
instrument, timeframe, and window. Assert their `run_key` matches. Change one field and assert it
differs.

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=DiscoveryStoreTest
```

Expected: compilation fails because the store does not exist.

- [ ] **Step 4: Add the schema**

Create tables `discovery_run`, `observation`, `observation_exclusion`, `forward_label`, `opportunity_zone`,
`zone_member`, `pattern_family`, `candidate_rule`, `exit_policy`, `monthly_result`,
`validation_trade`, and `experiment_event`. Enforce:

```sql
UNIQUE(run_id, instrument, timeframe_min, signal_ts, direction)
```

on `observation`, and use its key as the label foreign key. Store ordered feature and path JSON with
Jackson. Enable foreign keys and wrap batch writes in transactions.

- [ ] **Step 5: Implement run metadata and spent events**

`DiscoveryRun.runKey()` is SHA-256 over the canonical ordered metadata fields and full input-data
hash. `appendWindowSpent` inserts an append-only `window_spent` event and rejects a duplicate
`from/to` pair.

- [ ] **Step 6: Verify**

Run:

```bash
./mvnw test -Dtest=DiscoveryStoreTest
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery/store \
  src/main/resources/discovery \
  src/test/java/io/g3tech/axetrader/backtest/discovery/store
git commit -m "feat(discovery): persist versioned observations and labels"
git push
```

---

### Task 6: Empirical opportunity scores and independent zones

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityClass.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityScore.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityScorerV1.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityZone.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityZoneBuilder.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityScorerV1Test.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/analysis/OpportunityZoneBuilderTest.java`

**Interfaces:**
- `OpportunityClass`: `RUN`, `AMBIGUOUS`, `CHOP`.
- `OpportunityScorerV1.score(List<LabelledObservation>)` scores within direction and calendar month.
- `OpportunityZoneBuilder.build(List<OpportunityScore>)` follows the exact adjacency/MFE-time rule
  from spec section 5.3.

- [ ] **Step 1: Write percentile-score tests**

Create ten hand-labelled paths in one direction/month. V1 uses average percentile rank of:

```text
net MFE ATR (higher better)
MFE / max(0.1, abs(MAE-before-MFE)) (higher better)
directional efficiency (higher better)
time to MFE (lower better)
two-sided excursion (lower better)
```

Assert top 20% are `RUN`, bottom 40% are `CHOP`, and the middle 40% are `AMBIGUOUS`. Ties receive
the average rank and deterministic timestamp ordering.

- [ ] **Step 2: Write exact zone-boundary tests**

Assert same-direction run bars no more than one bar apart and with MFE timestamps no more than six
bars apart share a zone. Assert two consecutive non-run bars, opposite directions, or an MFE
timestamp separation above six bars creates a new zone.

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=OpportunityScorerV1Test,OpportunityZoneBuilderTest
```

Expected: compilation fails for the analysis types.

- [ ] **Step 4: Implement V1 scoring and zoning**

Make the score version literal `opportunity-v1`. Persist every component percentile plus the
composite score; never discard raw labels. Assign a stable zone id from direction, first signal
timestamp, last signal timestamp, and score version.

- [ ] **Step 5: Verify**

Run:

```bash
./mvnw test -Dtest=OpportunityScorerV1Test,OpportunityZoneBuilderTest
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery/analysis \
  src/test/java/io/g3tech/axetrader/backtest/discovery/analysis
git commit -m "feat(discovery): score paths and group opportunity zones"
git push
```

---

### Task 7: Conditional slices and compact rule mining

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/RuleClause.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/CandidateRule.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/ConditionalSlice.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/ConditionalSliceAnalyzer.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/analysis/ShallowRuleMiner.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/analysis/ConditionalSliceAnalyzerTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/analysis/ShallowRuleMinerTest.java`

**Interfaces:**

```java
public record RuleClause(String feature, Operator operator, double threshold) {
    public enum Operator { LT, LTE, GT, GTE }
    public boolean matches(FeatureVector vector);
}

public record CandidateRule(
        String id, Direction direction, List<RuleClause> clauses,
        int independentZones, double meanNetMfeAtr, double meanScore) {
    public boolean matches(ObservableState state);
}
```

- [ ] **Step 1: Write conditional-slice tests**

For a fixture where `pillar.volume_trend.activated=1` has mean net MFE `+1.2 ATR` and baseline is
`+0.4 ATR`, assert the analyzer reports the conditional delta, independent-zone count, and monthly
coverage. Bucket numeric features at derivation-set percentiles `20/40/60/80`.

- [ ] **Step 2: Write rule-miner constraint tests**

Use a synthetic dataset where only `support strengthening AND trend slope rising` identifies run
zones. Assert the returned rule:

- contains both clauses;
- has depth/clauses no greater than three during mining and four in the final rule;
- counts zones, not adjacent observations;
- never contains a label field;
- emits no more than ten families for a direction;
- is deterministic after input shuffling.

- [ ] **Step 3: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=ConditionalSliceAnalyzerTest,ShallowRuleMinerTest
```

Expected: compilation fails for the rule-analysis types.

- [ ] **Step 4: Implement deterministic depth-three mining**

At each node, evaluate observable-feature thresholds from the `20/40/60/80` derivation percentiles.
Choose the split with the largest improvement in mean zone-weighted opportunity score, breaking ties
by feature name, operator, then threshold. Require ten zones per leaf. Convert positive leaves into
rules, merge threshold variants with identical feature/operator sequences, and retain the ten
highest zone-weighted rules per direction.

- [ ] **Step 5: Implement candidate registration**

Canonicalise clauses by feature/operator/threshold, hash the canonical JSON for `CandidateRule.id`,
and store the derivation window before evaluating the next chronological month.

- [ ] **Step 6: Verify**

Run:

```bash
./mvnw test -Dtest=ConditionalSliceAnalyzerTest,ShallowRuleMinerTest
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery/analysis \
  src/test/java/io/g3tech/axetrader/backtest/discovery/analysis
git commit -m "feat(discovery): mine compact entry rules"
git push
```

---

### Task 8: Pattern-specific oracle and executable exit policies

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicy.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyGenerator.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyEvaluator.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitEvaluation.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/OracleExit.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/runner/TieredExitEngine.java`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyGeneratorTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyEvaluatorTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/runner/TieredExitEngineTest.java`

**Interfaces:**
- `ExitPolicy` carries one to three ATR-normalised tiers, fractions, stop rule, ratchet,
  zero-to-two invalidation clauses, and maximum holding bars `48`.
- `ExitEvaluation` carries entry/exit indices and prices, net P&L, exit reason, tier fills, capture
  ratio, and the observable invalidation clauses that fired.
- `List<ExitPolicy> ExitPolicyGenerator.generate(
  CandidateRule rule, List<LabelledObservation> derivationOccurrences)` returns at most twelve.
- `ExitEvaluation ExitPolicyEvaluator.evaluate(
  ObservableState entry, MarketSeries market, ExitPolicy policy,
  List<ObservableState> chronologicalStates)` uses sided exits and next-bar dynamic invalidation.

- [ ] **Step 1: Pin the existing tier engine before extraction**

Copy representative hand-calculated long, short, stop-wins-tie, multi-tier, ratchet, and time-stop
fixtures into `TieredExitEngineTest`. Do not edit either existing runner exit test.

- [ ] **Step 2: Write the finite-policy generator test**

Assert targets come only from positive-development-MFE ATR percentiles `25/50/75`; fractions come
only from:

```text
100
50/50
33/67
67/33
33/33/34
25/35/40
50/25/25
```

Assert stops come only from structural invalidation or MAE-before-MFE percentiles `50/75`, ratchets
are `NONE`, `BREAKEVEN_AFTER_T1`, or `LAGGED`, and only the best twelve derivation policies remain.

- [ ] **Step 3: Write oracle and dynamic-invalidation tests**

On a hand-built path, assert the oracle upper bound and executable capture ratio. Assert a
two-condition invalidation observed on bar `i` fills at bar `i+1`, not on bar `i`. Assert trading
close overrides remaining tiers.

- [ ] **Step 4: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=TieredExitEngineTest,ExitPolicyGeneratorTest,ExitPolicyEvaluatorTest
```

Expected: compilation fails for the new engine and policy types.

- [ ] **Step 5: Extract `TieredExitEngine`**

Move the existing tier-walk arithmetic out of `BacktestRunner`; leave package-compatible delegate
methods so both existing exit test classes compile and pass unchanged. Add a sided-series overload
used only by discovery. Preserve the conservative stop-first tie and next-bar ratchet timing.

- [ ] **Step 6: Implement generation, oracle, and evaluation**

Generate the predeclared combinations on derivation months, rank by net-profit/max-drawdown with
stable tie-breaking, and retain twelve. `OracleExit` selects the best feasible per-path result only
for opportunity/capture reporting. It is never convertible to `CandidateRule` or executable config.

- [ ] **Step 7: Verify all money tests**

Run:

```bash
./mvnw test -Dtest=BacktestRunnerIntrabarTest,BacktestRunnerTieredExitTest,TieredExitEngineTest,ExitPolicyGeneratorTest,ExitPolicyEvaluatorTest
```

Expected: all pass unchanged.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery/exit \
  src/main/java/io/g3tech/axetrader/backtest/runner/TieredExitEngine.java \
  src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java \
  src/test/java/io/g3tech/axetrader/backtest/discovery/exit \
  src/test/java/io/g3tech/axetrader/backtest/runner/TieredExitEngineTest.java
git commit -m "feat(discovery): derive pattern-specific exit policies"
git push
```

---

### Task 9: Non-overlapping executable validation and monthly gates

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/FrozenCandidate.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/ValidationTrade.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/MonthlyResult.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/ValidationSummary.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/ValidationStatistics.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/ExecutableValidator.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/PromotionGate.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/validation/ExecutableValidatorTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/validation/PromotionGateTest.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/discovery/validation/ValidationStatisticsTest.java`

**Interfaces:**
- `FrozenCandidate` contains immutable candidate id, `CandidateRule`, `ExitPolicy`, derivation
  window, feature-schema version, and score version.
- `ValidationTrade` contains candidate id, direction, entry/exit indices and timestamps,
  side-aware prices, net P&L, exit reason, tier fills, and capture ratio.
- `List<ValidationTrade> ExecutableValidator.run(
  FrozenCandidate candidate, MarketSeries market, List<ObservableState> states)`
- `Map<YearMonth, Double> ValidationStatistics.netByMonth(List<ValidationTrade> trades)`
- `PromotionGate.GateResult PromotionGate.evaluate(ValidationSummary summary)`

- [ ] **Step 1: Write non-overlap and next-bar tests**

Create a rule matching bars 10, 11, and 12 with an exit at bar 20. Assert only bar 10 opens a
trade. Add a later match at bar 21 and assert it opens. Assert entry price comes from bar 11's
direction side.

- [ ] **Step 2: Write monthly-statistics tests**

Add hand-calculated trades across six UTC calendar months and assert total net, net by month,
profitable-month percentage, median month, worst month, maximum drawdown, and
`totalNet/maxDrawdown`.

- [ ] **Step 3: Write exact promotion-gate tests**

Assert a summary passes only with positive total/median net, `>=70%` profitable sampled months,
`>=6` sampled months, `>=10` trades in each sampled month, `>=30` independent development zones,
and net/drawdown `>=1.0`. Assert a combined candidate fails when either enabled direction has
non-positive total net.

- [ ] **Step 4: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=ExecutableValidatorTest,PromotionGateTest,ValidationStatisticsTest
```

Expected: compilation fails for validation types and monthly methods.

- [ ] **Step 5: Implement chronological validation**

Sort states by signal time, skip all matching states while a position is open, and re-enable entry
only after the final exit bar. Use `ExitPolicyEvaluator` and side-aware prices. Do not subtract
`avgSpread`; the bid/ask path already includes it.

- [ ] **Step 6: Implement monthly metrics and gates**

Use `YearMonth.from(entryTime.atZone(UTC))`. Months with fewer than ten trades remain in output but
do not enter the promotion percentage denominator. Guard division by zero: a zero drawdown with
positive net has infinite ratio; empty results fail.

- [ ] **Step 7: Verify**

Run:

```bash
./mvnw test -Dtest=ExecutableValidatorTest,PromotionGateTest,ValidationStatisticsTest
```

Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/backtest/discovery/validation \
  src/test/java/io/g3tech/axetrader/backtest/discovery/validation
git commit -m "feat(discovery): validate frozen rules by month"
git push
```

---

### Task 10: Guarded discovery harness and compact report export

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryPipeline.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/DiscoveryRequest.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/FinalValidationService.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/FinalOosGate.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/report/DiscoveryReport.java`
- Create: `src/main/java/io/g3tech/axetrader/backtest/discovery/report/DiscoveryReportExporter.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/discovery/EmpiricalDiscoveryHarnessTest.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/discovery/FinalValidationHarnessTest.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/discovery/FinalValidationServiceTest.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/discovery/DiscoveryPipelineTest.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/discovery/validation/FinalOosGateTest.java`
- Create: `src/test/java/io/g3tech/axetrader/backtest/discovery/report/DiscoveryReportExporterTest.java`
- Modify: `.gitignore`

**Interfaces:**
- `DiscoveryReport DiscoveryPipeline.run(DiscoveryRequest request)` orchestrates Tasks 1–9.
- `DiscoveryReportExporter.export(Path, DiscoveryReport)` writes `discovery-report.json`.
- Harness properties: `discovery`, `discovery.from`, `discovery.to`, `discovery.persist`,
  `discovery.report`.
- Final-validation properties: `finalValidation=true` and `finalValidation.candidate=<frozen-id>`;
  no window properties are accepted.
- `DiscoveryRequest` contains instrument, timeframe, half-open development window, strategy config,
  persistence path, report path, source commit, and input-data hash.
- `DiscoveryReport` contains run metadata, pattern summaries, monthly results, direction summaries,
  and selected compact examples; it contains no full every-bar array.
- `FinalValidationService.run(candidateId)` accepts only a frozen promotion-passing candidate,
  reads exactly the protected window once, appends `window_spent`, and applies `FinalOosGate`.

- [ ] **Step 1: Write a small end-to-end pipeline test**

Use an in-memory 300-bar sided fixture containing one long and one short synthetic run. Assert the
pipeline emits two states per eligible bar, labels only complete paths, creates zones, mines rules,
evaluates exits, validates without overlap, and writes all entities under one deterministic run key.

- [ ] **Step 2: Write report-contract tests**

Assert JSON contains `run`, `patterns`, `monthly_results`, and `examples`. Each example must include
observable features, pillar transitions, oracle result, executable result, rule clauses, and a
bounded chart window. Assert no full every-bar observation array is exported.

- [ ] **Step 3: Write harness and final-OOS guard tests**

Assert the normal harness rejects any window intersecting the protected interval before loading
history. Assert `FinalValidationService` rejects a missing, mutable, or promotion-failing candidate;
rejects any window other than the exact protected interval; and refuses a second run after
`window_spent`.

For `FinalOosGate`, assert pass requires positive total net, positive median month, at least 50% of
sampled OOS months profitable, and max-drawdown-per-trade no worse than `1.5 ×` development. Months
with fewer than ten trades remain reported but unsampled.

- [ ] **Step 4: Run tests and confirm failure**

Run:

```bash
./mvnw test -Dtest=DiscoveryPipelineTest,DiscoveryReportExporterTest,FinalValidationServiceTest,FinalOosGateTest
```

Expected: compilation fails for the pipeline and exporter.

- [ ] **Step 5: Implement orchestration and deterministic report selection**

Wire extraction, labelling, storage, scoring, zoning, mining, exit generation, and walk-forward
validation. Select best, median, worst, false-positive, and missed-run examples deterministically by
score then timestamp. Default paths:

```text
experiments/discovery.sqlite
dashboard/discovery-report.json
```

Sort development months chronologically. Use the first three complete months as the initial
derivation window; evaluate registered candidates on month four. For each later fold, expand the
derivation history but never alter an existing candidate id or its frozen exit policy. A changed
rule or policy is a new candidate with results starting in its next month. Apply the discovery gate
(positive total net and at least 50% profitable months with five trades) before exit generation, and
apply `PromotionGate` only to one unchanged candidate that has accumulated six sampled
non-overlapping months.

Add both generated files to `.gitignore`.

- [ ] **Step 6: Implement but do not execute final validation**

`FinalValidationService` loads the immutable candidate JSON and development summary from
`DiscoveryStore`, verifies `PromotionGate`, verifies no `window_spent` event exists, and then runs
the same `ExecutableValidator` over exactly `[OOS_FROM,OOS_TO)`. Append the spent event before
returning the result so even a failing OOS result consumes the window. The service exposes no
configuration mutation or rule-mining APIs. Add `FinalValidationHarnessTest`, gated by
`@EnabledIfSystemProperty(named = "finalValidation", matches = "true")`, as the sole command entry
point; it requires the frozen candidate id and accepts no from/to overrides.

- [ ] **Step 7: Add the opt-in development harness**

Follow `ConfluenceSweepTest`'s Spring test pattern and gate with:

```java
@EnabledIfSystemProperty(named = "discovery", matches = "true")
```

Default to the non-protected development window. Require explicit `discovery.from/to` for any
override and call `DiscoveryWindowPolicy` before repository access.

- [ ] **Step 8: Verify without running the real discovery or final OOS**

Run:

```bash
./mvnw test -Dtest=DiscoveryPipelineTest,DiscoveryReportExporterTest,FinalValidationServiceTest,FinalOosGateTest
./mvnw test
```

Expected: all pass; the opt-in harness is skipped.

- [ ] **Step 9: Commit**

```bash
git add .gitignore \
  src/main/java/io/g3tech/axetrader/backtest/discovery \
  src/test/java/io/g3tech/axetrader/backtest/discovery
git commit -m "feat(discovery): orchestrate guarded empirical runs"
git push
```

---

### Task 11: Dashboard discovery audit view

**Files:**
- Create: `dashboard/migrations/0003_discovery_reports.sql`
- Create: `dashboard/scripts/discovery-sql.ts`
- Create: `dashboard/scripts/push-discovery.ts`
- Create: `dashboard/src/routes/discovery.ts`
- Modify: `dashboard/src/index.ts`
- Modify: `dashboard/src/schema.ts`
- Modify: `dashboard/frontend/src/types.ts`
- Modify: `dashboard/frontend/src/api.ts`
- Create: `dashboard/frontend/src/components/DiscoveryOverview.tsx`
- Modify: `dashboard/frontend/src/App.tsx`
- Modify: `dashboard/frontend/src/styles.css`
- Create: `dashboard/test/discovery-schema.test.ts`
- Create: `dashboard/test/discovery.test.ts`
- Create: `dashboard/test/discovery-sql.test.ts`
- Create: `dashboard/frontend/src/components/DiscoveryOverview.test.tsx`
- Modify: `dashboard/package.json`

**Interfaces:**
- D1 tables: `discovery_runs`, `discovery_patterns`, `discovery_months`,
  `discovery_examples`.
- Endpoints:
  - `GET /api/discovery/runs`
  - `GET /api/discovery/runs/:id`
- `npm run push:discovery -- discovery-report.json`

- [ ] **Step 1: Write migration and API tests first**

Test idempotent report replacement, pattern/month/example joins, `404` for missing run, and compact
list responses that omit chart bars. Assert manual feedback/marks tables are untouched.

- [ ] **Step 2: Write push-SQL contract tests**

Use a minimal report fixture and assert deletes occur child-first, inserts escape text, JSON fields
round-trip, and pushing the same run id twice does not duplicate rows.

- [ ] **Step 3: Write frontend tests**

Assert the new `Discovery` tab renders:

- total net, max drawdown, profitable-month percentage, and net/drawdown;
- long and short results separately;
- monthly results with sample-size visibility;
- rule clauses and independent-zone counts;
- oracle-versus-executable capture;
- best, median, worst, false-positive, and missed-run examples.

- [ ] **Step 4: Run tests and confirm failure**

Run from `dashboard/`:

```bash
npm test
```

Expected: tests fail because migration, routes, script, and component do not exist.

- [ ] **Step 5: Implement D1 storage, push script, and routes**

Store only compact report data. Keep full bar windows on selected examples as JSON. Register
`discoveryRoutes` in `src/index.ts`; validate required report fields before returning success.

- [ ] **Step 6: Implement the audit UI**

Add `discovery` to the tab union and show `DiscoveryOverview` only for a selected discovery run.
Reuse existing KPI and candle-chart conventions where possible. Manual marks stay comments; do not
send them to discovery endpoints or display them as labels.

- [ ] **Step 7: Verify dashboard**

Run:

```bash
npm test
npm run typecheck
npm run build
```

Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add dashboard
git commit -m "feat(dashboard): audit empirical discovery reports"
git push
```

---

### Task 12: Development run, acceptance verification, and durable documentation

**Files:**
- Modify: `docs/dev-environment.md`
- Modify: `TODO.md`
- Generated and ignored: `experiments/discovery.sqlite`
- Generated and ignored: `dashboard/discovery-report.json`

**Interfaces:**
- No new code interfaces. This task proves the implemented pipeline works on real development data.

- [ ] **Step 1: Verify the complete repository before the long run**

Run:

```bash
./mvnw test
cd dashboard
npm test
npm run typecheck
npm run build
cd ..
```

Expected: every command passes.

- [ ] **Step 2: Run discovery on development history only**

Run:

```bash
./mvnw test -Dtest=EmpiricalDiscoveryHarnessTest -Ddiscovery=true \
  -Ddiscovery.from=2024-12-04T23:20:00Z \
  -Ddiscovery.to=2026-01-01T00:00:00Z \
  -Ddiscovery.persist=true \
  -Ddiscovery.report=dashboard/discovery-report.json
```

Expected: the run completes without reading Jan–May 2026 and prints observation, label, gap,
zone, pattern, policy, fold, and validation counts.

- [ ] **Step 3: Inspect acceptance invariants**

Query `experiments/discovery.sqlite` and verify:

```sql
SELECT COUNT(*) FROM observation GROUP BY direction;
SELECT status, COUNT(*) FROM forward_label GROUP BY status;
SELECT direction, COUNT(*) FROM opportunity_zone GROUP BY direction;
SELECT direction, COUNT(*) FROM candidate_rule GROUP BY direction;
SELECT year_month, trades, net_pnl FROM monthly_result ORDER BY year_month;
SELECT COUNT(*) FROM observation
 WHERE signal_ts >= '2026-01-01T00:00:00Z';
```

Expected: long/short observation counts match; statuses are explicit; both directions are analysed;
candidate counts are at most ten per direction; monthly rows are present; the final query returns
zero.

- [ ] **Step 4: Verify deterministic regeneration**

Move the ignored DB/report aside, rerun the same command, and compare canonical run keys, observation
counts, label hashes, zone ids, rule ids, policy ids, rankings, and report JSON after excluding only
the generation timestamp. Expected: identical.

- [ ] **Step 5: Document commands and record real results**

Add the development-run command, generated artifact paths, report-push command, and protected-window
warning to `docs/dev-environment.md`. Add the actual run id, commit, counts, monthly validation
table, promoted or falsified candidates, and next decision to `TODO.md`. If no candidate passes,
record that negative result without loosening any gate.

- [ ] **Step 6: Push the compact report to local D1 and smoke-test**

Run from `dashboard/`:

```bash
npm run migrate:local
npm run push:discovery -- discovery-report.json
npm run dev
```

Open the local dashboard and verify the discovery tab, monthly table, rule clauses, direction split,
and selected examples match the JSON. Stop the dev server after verification.

- [ ] **Step 7: Final verification**

Run:

```bash
./mvnw test
cd dashboard
npm test
npm run typecheck
npm run build
cd ..
git diff --check
git status --short
```

Expected: all tests/checks pass; only the intended documentation changes are uncommitted; generated
SQLite/JSON artifacts remain ignored.

- [ ] **Step 8: Commit**

```bash
git add docs/dev-environment.md TODO.md
git commit -m "docs(discovery): record first empirical development run"
git push
```

Do not run or spend the final OOS window in this plan. Stop at the promotion decision and ask the
human before creating a final-validation workflow.
