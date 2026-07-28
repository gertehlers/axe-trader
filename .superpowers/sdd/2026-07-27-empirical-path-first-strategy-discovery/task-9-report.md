# Task 9 report: non-overlapping executable validation and monthly gates

## Scope

Implemented the validation package without reading or executing final OOS validation. The validator
uses only `ObservableState`, `MarketSeries`, and the frozen `ExitPolicy`; it has no dependency on
`ForwardPathLabel` or the protected OOS window.

## TDD RED evidence

Tests were added first:

- `ExecutableValidatorTest` pins chronological non-overlap: matching signal bars 10, 11, and 12
  produce only the bar-10 entry, a signal at bar 21 re-enters after the bar-20 final exit, and the
  long entry is the ask close at bar 11 (`101.0`).
- `ValidationStatisticsTest` pins UTC `YearMonth` aggregation, visibility of a seven-trade month,
  sampled-only profitable-month denominator, hand-calculated median/worst month/drawdown/ratio,
  zero-drawdown positive-net infinity, and the empty-result NaN ratio.
- `PromotionGateTest` pins the exact zone, total-net, median, profitable sampled-month, sampled-month
  count, ten-trades-per-sampled-month, and net/drawdown thresholds, plus the combined-direction rule.

First command (before the production classes existed):

```text
./mvnw test -Dtest=ExecutableValidatorTest,PromotionGateTest,ValidationStatisticsTest
```

It failed at test compilation as expected, with missing `FrozenCandidate`, `ValidationSummary`, and
`ValidationTrade` symbols. This is the recorded RED state. A subsequent test-fixture type correction
was required because `Map.of(..., 10)` inferred `Integer` instead of the expected `Double`; no
production behaviour changed for that correction.

## Decisions

- `FrozenCandidate` locks a candidate id, rule, matching exit policy, half-open derivation window,
  feature-schema version, and score version. It rejects a policy for another rule.
- `ExecutableValidator` sorts by signal timestamp, invokes `ExitPolicyEvaluator` for fills and exit
  semantics, and does not admit another entry until the signal bar is strictly after the prior final
  exit bar. It takes entry/exit timestamps from the market bars and does not subtract any average
  spread; the evaluator's bid/ask execution path supplies cost once.
- `ValidationTrade` retains candidate/direction, entry and exit indexes/times/prices, P&L, exit
  reason, tier fills, and capture ratio.
- Monthly keys are `YearMonth.from(entryTime.atZone(UTC))`. `MonthlyResult.sampled()` is exactly
  ten trades. Every month is retained; only sampled months contribute to the profitable-month
  percentage.
- Drawdown is peak-to-trough cumulative chronological P&L. A positive result with no drawdown gets
  `Double.POSITIVE_INFINITY`; an empty result gets `NaN`, which cannot pass the promotion threshold.
- `ValidationSummary` includes enabled directions and a net total per direction, so `PromotionGate`
  can reject a combined candidate whenever any enabled direction is non-positive.

## Files

- Added `src/main/java/io/g3tech/axetrader/backtest/discovery/validation/`
  - `FrozenCandidate.java`, `ValidationTrade.java`, `MonthlyResult.java`, `ValidationSummary.java`
  - `ValidationStatistics.java`, `ExecutableValidator.java`, `PromotionGate.java`
- Added `src/test/java/io/g3tech/axetrader/backtest/discovery/validation/`
  - `ExecutableValidatorTest.java`, `ValidationStatisticsTest.java`, `PromotionGateTest.java`

## Verification

Focused verification:

```text
./mvnw test -Dtest=ExecutableValidatorTest,PromotionGateTest,ValidationStatisticsTest
```

Result: 5 tests run, 0 failures, 0 errors.

Full Java verification:

```text
./mvnw test
```

Result: 127 tests run, 0 failures, 0 errors, 1 skipped opt-in sweep. No final OOS command was run.

`git diff --check` produced no whitespace errors. The user-owned
`output/charts/runner-results.html` modification was left untouched and is not part of this task.

## Commit

The task code, tests, and this report are committed together with message
`feat(discovery): validate frozen rules by month`; the handoff records the resulting hash.

## Concerns

- The full Maven suite emits pre-existing dependency/runtime warnings (multiple SLF4J providers,
  CycloneDX schema keywords, and Java native-access notices); they do not fail tests.
- Combined candidates are represented at the summary/gate level because a `FrozenCandidate` and its
  `ExitPolicy` are intentionally single-direction. Task 10 orchestration must build combined
  summaries from independently frozen long and short results rather than merging rules or policies.

## Fix round 1: NaN promotion-gate bypass

### Root cause

`ValidationSummary` permits `NaN` aggregate values so empty statistics can be represented for a
failed gate. Java comparisons such as `Double.NaN <= 0.0` are false, however, so the old
`PromotionGate` checks for median net and enabled-direction net accidentally accepted those invalid
values when all other conditions passed. The same issue left a non-finite drawdown unchecked.

### TDD evidence

Before changing production code, `PromotionGateTest` gained two otherwise-qualifying summaries:

- a `NaN` median monthly net;
- a `NaN` total for the enabled long direction.

RED command:

```text
./mvnw test -Dtest=PromotionGateTest
```

RED result: 4 tests run, 2 failures. Both new assertions reported that `PromotionGate.evaluate(...).passed()`
was `true`, proving the IEEE-754 comparison bypass.

### Fix and verification

`PromotionGate` now uses `!(value > 0.0)` for required positive totals and median/direction values,
which rejects zero, negatives, and `NaN`. It also rejects a drawdown unless it is finite and
non-negative. The summary remains able to express empty statistics; the gate is the strict
promotion boundary.

GREEN commands:

```text
./mvnw test -Dtest=ExecutableValidatorTest,PromotionGateTest,ValidationStatisticsTest
./mvnw test
```

GREEN results: focused 7 tests run, 0 failures/errors; full Java suite 129 tests run, 0
failures/errors, 1 skipped opt-in sweep. No final OOS validation was run.

Commit: `fix(discovery): reject non-finite promotion metrics` (hash recorded in the handoff).
