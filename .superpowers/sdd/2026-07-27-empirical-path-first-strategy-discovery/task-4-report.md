# Task 4 report — Forward path labels and leakage barrier

## Outcome

Implemented side-aware, future-only four-hour path labels in commit
`ac9e20a780c194f158b0be7cc4f4a9361a82ca7a` (`feat(discovery): label four-hour forward paths`).
Pushed successfully to `origin/feature/empirical-path-first-discovery`.

## RED evidence

Command:

```text
./mvnw test -Dtest=ForwardPathLabellerTest,LeakageBoundaryTest
```

Result: exit 1, as expected before implementation. The test compiler reported:

```text
cannot find symbol: class ForwardPathLabel
cannot find symbol: class LabelStatus
```

This established the new label model and labeller API as the missing production behavior.

## GREEN evidence

Commands and exact result summaries:

```text
./mvnw test -Dtest=ForwardPathLabellerTest,LeakageBoundaryTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

./mvnw test -Dtest=BacktestRunnerIntrabarTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

./mvnw test -Dtest=BacktestRunnerTieredExitTest
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

./mvnw test
Tests run: 90, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

The full suite's single skipped test is the pre-existing skipped `ConfluenceSweepTest` case. Maven
also emitted existing dependency/runtime warnings (multiple SLF4J providers and CycloneDX schema
keywords); neither affects the test result.

## Files changed

- `src/main/java/io/g3tech/axetrader/backtest/discovery/ForwardPathLabeller.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/model/LabelStatus.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/model/PathPoint.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/model/ForwardPathLabel.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/model/LabelledObservation.java`
- `src/test/java/io/g3tech/axetrader/backtest/discovery/ForwardPathLabellerTest.java`
- `src/test/java/io/g3tech/axetrader/backtest/discovery/LeakageBoundaryTest.java`
- `src/test/java/io/g3tech/axetrader/backtest/discovery/TestObservations.java`

## Self-review

- The label reads entries through `MarketSeries.entryPrice` and exits through
  `MarketSeries.exitBar`, so long paths use ask entry/bid exits and short paths use bid entry/ask
  exits. No legacy average spread is subtracted.
- The fill is `state.entryIndex()`; path points start on the following executable bar and end at the
  earlier of 48 such bars or the known broker-close bar.
- All bid/ask/mid bars must be aligned and continuously five minutes apart. An absent boundary,
  missing bar, misalignment, or unexplained gap produces `INCOMPLETE_GAP`; an unavailable entry bar
  produces `NO_EXECUTABLE_NEXT_BAR`.
- Only complete-horizon and known-trading-close labels are eligible for discovery.
- `ObservableState` was not changed. Future values remain in `ForwardPathLabel` and the
  offline-only `LabelledObservation` join; the leakage test confirms future mutations alter labels
  without modifying the already constructed observable state.
- Hand-calculated long and short tests assert MFE +3, MAE -1, excursion ordering, and side-aware
  entry pricing. Additional tests cover the exact 48 exit-bar horizon, known 12-bar close,
  incomplete gap, and unexecutable next bar.
- The full suite regenerated `output/charts/runner-results.html`; that test artifact was restored
  before staging. Only Task 4 source/test files were committed.

## Commit and push

```text
Commit: ac9e20a780c194f158b0be7cc4f4a9361a82ca7a
Push: To github.com:gertehlers/axe-trader.git
      abc363e..ac9e20a  feature/empirical-path-first-discovery -> feature/empirical-path-first-discovery
```

## Concerns

None. `TradingSessionCalendar` intentionally exposes no distinction between an absent known future
boundary and an unknown boundary, so the labeller treats both as incomplete rather than guessing a
cash-market close; this is the required fail-closed behavior.

## Fix round 1 — leakage API boundary

### Change

`ForwardPathLabeller.label` now accepts only the observable scalar metadata needed to calculate a
label (`Direction`, `entryIndex`, and `entryAtr`) plus market/session inputs; it no longer accepts
`ObservableState`. The existing `LabelledObservation(ObservableState, ForwardPathLabel)` remains
in `io.g3tech.axetrader.backtest.discovery.model`, the offline discovery package.

`LeakageBoundaryTest` now extracts/copies an observable state before mutating the very same
`MarketSeries` future bar via ta4j's `Bar.addPrice`. It proves the new label changes while the full
previously extracted state remains equal to its independent copy. A second test prevents the
labeller from accepting `ObservableState` in any method signature.

### RED evidence

Command:

```text
./mvnw test -Dtest=ForwardPathLabellerTest,LeakageBoundaryTest
```

Result: exit 1, as expected before the production API change. Test compilation failed eight times
with `method label ... cannot be applied to given types`; the old API required
`ObservableState, MarketSeries, TradingSessionCalendar`, while the regression required
`Direction, int, double, MarketSeries, TradingSessionCalendar`.

### GREEN evidence

Commands and exact result summaries:

```text
./mvnw test -Dtest=ForwardPathLabellerTest,LeakageBoundaryTest
ForwardPathLabellerTest: Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
LeakageBoundaryTest: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
Total: Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

./mvnw test -Dtest=BacktestRunnerIntrabarTest,BacktestRunnerTieredExitTest
BacktestRunnerIntrabarTest: Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BacktestRunnerTieredExitTest: Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
Total: Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

./mvnw test
Tests run: 91, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

The full-suite skip is the existing `ConfluenceSweepTest` skip. Maven again emitted existing
SLF4J-provider, CycloneDX-schema, and JDK native-access warnings; none caused test failures.

### Commit and push

```text
Fix commit: 0a1551c591f18c29049e1391ebce86b1e7fd86a7
Push: To github.com:gertehlers/axe-trader.git
      ac9e20a..0a1551c  feature/empirical-path-first-discovery -> feature/empirical-path-first-discovery
```

### Concerns

None. The public labeller API has intentionally become a scalar-metadata API: offline callers can
join its future-only result to `ObservableState` solely through `LabelledObservation`, while no
labeller method can accidentally return label data from an `ObservableState` parameter.
