# Empirical Discovery Progress — 2026-07-28

## Done

- Tasks 1–7 were complete before this continuation.
- Task 8 is complete and reviewed: extracted the reusable tiered exit engine; added bounded,
  side-aware pattern exit policies, oracle reporting, next-bar dynamic invalidation, and
  deterministic policy selection using cumulative drawdown. The existing runner exit tests remain
  unchanged and pass.
- Task 9 is complete and reviewed: added chronological, one-position validation; UTC monthly
  performance statistics; and the frozen-candidate promotion gate. The gate rejects non-finite
  metrics and prevents an enabled losing direction from being masked by another direction.
- The protected Jan–May 2026 final OOS window was not read or executed.

## Next

- Task 10: implement the guarded discovery harness and compact report export. It must reject normal
  runs that intersect the protected OOS window and must not execute final validation.
- Task 11 follows with the dashboard audit view, then Task 12 runs development-history acceptance
  verification and documents the first real discovery result.

## Current verification

- Latest full Maven run: 131 tests passed, 0 failures, 1 skipped.
- The only pre-existing working-tree change is `output/charts/runner-results.html`; it was not
  touched by this work.
