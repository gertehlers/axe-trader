# Task 10 escalation fix report

Date: 2026-07-28

## Fixes

- Final OOS summaries retain every protected calendar month, while median monthly net and
  profitable-month percentage are calculated from sampled months only. `FinalOosGate` also
  derives its median directly from sampled months.
- The protected OOS window is atomically claimed in SQLite before the protected loader runs.
  The claim remains spent for failed final gates and loader/evaluation failures, and competing
  validation attempts cannot read the window.
- `false-positive` and `missed-run` examples are omitted when no observation actually satisfies
  the corresponding definition.
- Frozen candidate IDs include the discovery run key. Lookup rejects ambiguous legacy,
  content-only IDs rather than selecting the earliest matching run.
- Walk-forward folds now start with exactly three complete derivation months, exclude partial
  leading months, and expand by one complete month for each subsequent evaluation.

## Regression evidence

Expected-red commands:

```text
./mvnw -q -Dtest=FinalValidationServiceTest test
```

Result before implementation: test compilation failed because the sampled protected-month
summary seam was not available. Source inspection confirmed that the window claim occurred only
after loading/evaluation; the green regression invokes a competing service from inside the
loader and proves that its loader is never reached.

```text
./mvnw -q -Dtest=DiscoveryPipelineTest,DiscoveryStoreTest test
```

Result before implementation: test compilation failed because run-scoped `frozenId` and
`walkForwardFolds` did not exist. The previous report code always emitted five examples, and
candidate lookup returned its first matching row.

Focused green command:

```text
./mvnw -q -Dtest=DiscoveryPipelineTest,DiscoveryStoreTest,FinalValidationServiceTest,FinalOosGateTest test
```

Result: exit 0; all focused regression tests passed.

Full verification command:

```text
./mvnw test
```

Result:

```text
Tests run: 147, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
```

Diff hygiene:

```text
git diff --check
```

Result: exit 0. The pre-existing generated file `output/charts/runner-results.html` was left
unstaged and is not part of this fix.
