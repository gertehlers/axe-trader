# Task 8 report: pattern-specific oracle and executable exit policies

## Starting point

- Worktree: `/Users/gertehlers/Development/projects/axe-trader/.worktrees/empirical-path-first-discovery`
- Starting HEAD: `463c7a870f1ca9534a15b3b3d40e2ebcbe80ec2e`
- The pre-existing modification to `output/charts/runner-results.html` was left untouched and was not staged.

## TDD evidence

### RED

Initial required focused command:

```text
./mvnw test -Dtest=TieredExitEngineTest,ExitPolicyGeneratorTest,ExitPolicyEvaluatorTest
```

It failed during test compilation exactly because the new production API did not exist:

```text
package ExitPolicy does not exist
cannot find symbol: class ExitPolicy
```

After the first implementation pass, the tests exposed two real evaluator/fixture boundary issues:

- evaluation rejected an entry whose fixture did not provide a post-entry bar;
- an invalidation detected on the last available executable bar incorrectly became `END` instead of a next-bar invalidation fill.

After the structural-stop clarification, the generator test was narrowed to require MAE P50/P75 stops only. A subsequent focused red test for a two-condition invalidation failed as intended because the evaluator used OR semantics and exited at index 2 instead of the expected index 3:

```text
expected: 3
 but was: 2
```

The evaluator was then changed to require all invalidation clauses and schedule the exit at the next executable bar.

### GREEN

Focused command:

```text
./mvnw test -Dtest=TieredExitEngineTest,ExitPolicyGeneratorTest,ExitPolicyEvaluatorTest
```

Result: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

Required money-test command:

```text
./mvnw test -Dtest=BacktestRunnerIntrabarTest,BacktestRunnerTieredExitTest,TieredExitEngineTest,ExitPolicyGeneratorTest,ExitPolicyEvaluatorTest
```

Result: `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`.

`git diff --check` also exited successfully before the implementation commit.

## Implementation decisions

- Extracted the tier walk into `TieredExitEngine`; `BacktestRunner` retains package-compatible delegate records/methods, preserving existing callers and tests.
- The engine checks stops before targets on every bar, so an intrabar stop/target tie remains conservative and banks no target. Ratchets are calculated only after a bar has resolved, making them effective from the following bar.
- Added the discovery-side engine overload which reads the correct executable exit bar: bid for long exits and ask for short exits. Discovery P&L is computed directly from those fills with no legacy spread deduction.
- `ExitPolicyGenerator` builds only finite target/fraction/ratchet combinations and ranks them deterministically by its derivation net-profit-to-drawdown proxy, retaining twelve at most.
- `OracleExit` is a separate hindsight-only record for per-path opportunity/capture diagnostics; it is not an executable policy or candidate rule.
- Dynamic invalidation clauses are ANDed, evaluated after the observed bar completes, and fill at the next bar's sided open. Trading close fills any remaining position at the sided close and takes precedence over a same-close invalidation.

## Changed files

- `src/main/java/io/g3tech/axetrader/backtest/runner/BacktestRunner.java`
- `src/main/java/io/g3tech/axetrader/backtest/runner/ExitReason.java`
- `src/main/java/io/g3tech/axetrader/backtest/runner/TieredExitEngine.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicy.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyGenerator.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyEvaluator.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/ExitEvaluation.java`
- `src/main/java/io/g3tech/axetrader/backtest/discovery/exit/OracleExit.java`
- `src/test/java/io/g3tech/axetrader/backtest/runner/TieredExitEngineTest.java`
- `src/test/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyGeneratorTest.java`
- `src/test/java/io/g3tech/axetrader/backtest/discovery/exit/ExitPolicyEvaluatorTest.java`

## Commits

- `2aad122823567f518da74ff59b25ad3c09621138 feat(discovery): derive pattern-specific exit policies`

## Concern / downstream capability gap

`CandidateRule` and `ObservableState` currently do not carry an explicit entry-time structural invalidation price or ATR distance. Per instruction, the generator therefore omits structural-stop candidates rather than misrepresenting a MAE proxy as structural; it generates only MAE-before-MFE P50/P75 stops. A downstream data-model task is needed before genuine structural-stop policies can be generated.
