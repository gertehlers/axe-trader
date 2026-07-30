# Capital US500 Historical Re-ingestion Runbook

**Goal:** Replace the local US500 minute-history database with an independently fetched and audited Capital.com copy, then run empirical discovery only on the permitted development window.

**Status (2026-07-30):** The re-ingestion implementation is on `feature/reusable-history-reingestion` at `11655df` plus the public-entrypoint fix in the working tree. Focused importer tests pass (30 tests). The first live probe reached Capital but was rejected before any price request with `401 {"errorCode":"error.null.accountId"}`. No staging database was created and no active database was read, changed, or deleted.

## Safety rules

- The protected discovery OOS window is `2026-01-01T00:00:00Z` to `2026-05-02T00:00:00Z`; never request it with `discovery-window=true`.
- `probe` is read-only: it neither creates nor opens a SQLite database.
- `stage` writes only the explicit staging path. It does not touch `data/axe-trader.sqlite`.
- `promote` re-runs the audit, creates a timestamped backup of the active database, then atomically replaces it. The user has authorized replacement of the active DB after a clean audit.
- Do not bypass an audit failure, relax a gate, or hand-edit imported bid/ask values.

## 1. Repair Capital access before retrying

The current `.env` supplies `CAPITAL_API_USER`, `CAPITAL_API_PASSWORD`, and `CAPITAL_API_KEY`, but Capital rejects session creation with `error.null.accountId`. Resolve that in Capital’s demo API/account configuration first; the importer deliberately has no account-id override because Capital’s session API normally selects the account from the authenticated identity.

Confirm only that the local variables are populated; never print their values:

```bash
for name in CAPITAL_API_USER CAPITAL_API_PASSWORD CAPITAL_API_KEY; do
  test -n "${(P)name}" && print "$name=set" || print "$name=missing"
done
```

If the same `401` remains after renewing or correcting the Capital demo credentials, stop and capture the response code and error body. Do not continue to staging.

## 2. Run the three-minute read-only probe

From the linked re-ingestion worktree:

```bash
cd /Users/gertehlers/Development/projects/axe-trader/.worktrees/reusable-history-reingestion
set -a
source ../../.env
set +a
./mvnw spring-boot:run -Dspring-boot.run.arguments='--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=probe --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2025-01-20T16:20:00Z --axe-trader.history-import.to=2025-01-20T16:23:00Z --axe-trader.history-import.staging-database=target/capital-us500-probe.sqlite --axe-trader.history-import.active-database=data/axe-trader.sqlite --axe-trader.history-import.discovery-window=false'
```

Success evidence: a log line reporting three prices and a 64-character payload hash. Confirm no probe file was created:

```bash
test ! -e target/capital-us500-probe.sqlite
```

## 3. Stage the development-only history

Use a new staging path and the half-open development window. This endpoint may return at most 1,000 bars per request; the command paginates without overlap.

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments='--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=stage --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-12-04T23:20:00Z --axe-trader.history-import.to=2026-01-01T00:00:00Z --axe-trader.history-import.staging-database=data/us500-development-history.staging.sqlite --axe-trader.history-import.active-database=data/axe-trader.sqlite --axe-trader.history-import.discovery-window=true'
```

Success evidence: `History staging audit passed` with non-zero rows, zero duplicate/invalid/gap/crossed/out-of-window counts. Any other result is a stop condition; retain the staging DB for diagnosis and do not promote.

## 4. Explicitly promote only a clean staging database

Re-use exactly the stage request bounds and path. Promotion performs the final audit again, makes a dated sibling backup, then atomically replaces `data/axe-trader.sqlite`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments='--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=promote --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-12-04T23:20:00Z --axe-trader.history-import.to=2026-01-01T00:00:00Z --axe-trader.history-import.staging-database=data/us500-development-history.staging.sqlite --axe-trader.history-import.active-database=data/axe-trader.sqlite --axe-trader.history-import.discovery-window=true'
```

Verify the replacement and backup exist before proceeding:

```bash
ls -lh data/axe-trader.sqlite data/axe-trader.sqlite.backup-*
sqlite3 data/axe-trader.sqlite 'select min(snapshot_time_utc), max(snapshot_time_utc), count(*) from historical_price;'
sqlite3 data/axe-trader.sqlite 'select sum(case when open_ask < open_bid or high_ask < high_bid or low_ask < low_bid or close_ask < close_bid then 1 else 0 end) as crossed_rows from historical_price;'
```

Expected: coverage reaches the requested half-open window, and `crossed_rows` is `0`.

## 5. Run discovery and make it visible in the dashboard

Only after promotion and only over the same development-only range:

```bash
./mvnw test -Dtest=EmpiricalDiscoveryHarnessTest -Ddiscovery=true -Ddiscovery.from=2024-12-04T23:20:00Z -Ddiscovery.to=2026-01-01T00:00:00Z -Ddiscovery.persist=true -Ddiscovery.report=dashboard/discovery-report.json
cd dashboard
npm run migrate:local
npm run push:discovery -- discovery-report.json
npm run dev
```

Open `http://localhost:8787`, choose **Discovery**, and verify that the run, monthly results, rule clauses, and examples match `dashboard/discovery-report.json`. Do not run final OOS validation or request the protected Jan–May window without a separate human decision.

## Recovery

- A failed probe or stage never changes the active DB.
- A failed promotion audit never changes the active DB.
- If a promoted DB must be rolled back, stop and use the dated backup created by the promotion command; do not delete any backup before validating it with SQLite.
