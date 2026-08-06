# Local price-history operations

Price history is owned by the local SQLite database and its committed gzip archive. The former
Cloudflare D1 price-history import workflow is retired: do not use Wrangler, the dashboard, or D1
to build or repair this dataset.

Run the commands from the repository root. The explicit Config Data import points at the main
checkout's credential file because linked worktrees do not contain a copy. Never print or copy the
file's values.

There are two workflows. **Update** is the routine one: it tops instruments up incrementally and is
what you want almost always. **Probe / stage / promote** rebuilds a dataset from nothing and replaces
the active database wholesale — reach for it only when the existing data is unusable.

## Update

`update` imports every minute between an instrument's last stored bar and the last completed UTC
minute. The in-progress minute is deliberately excluded, since a partial candle would be stored once
and never revisited. Cursors are derived from `MAX(snapshot_time_utc)` per source/epic/resolution, so
there is no cursor state to keep in sync and an interrupted run simply resumes with a shorter window.

```bash
# Top up every stored instrument from its own cursor.
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update"

# Top up one instrument.
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update --axe-trader.history-import.epic=US500"

# Seed a new instrument (requires an explicit resolution and start).
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update --axe-trader.history-import.epic=GOLD --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z"
```

Each delta stages into its own file under `data/.staging/`, is audited by the same gate that governs
promotion, and is only then merged into `data/axe-trader.sqlite` inside a single transaction. Dirty
source candles never enter `historical_price`; they are recorded in `price_exclusion` with a reason
and reported in the run summary, and they do **not** block the merge. Duplicates, counts that fail to
reconcile, and unexplained continuity gaps do block it. Empty and 404 provider windows are recognised
session closures — weekend and holiday top-ups are the ordinary case, not a failure.

A failed instrument does not abort the others, but the process exits non-zero and **leaves its
staging file under `data/.staging/` as evidence**. Delete it only after you have read it. Rerunning
`update` is always safe: the merge is idempotent against the unique index on
`(source, epic, resolution, snapshot_time_utc)`.

Seeding fails closed. An instrument with no stored rows has no cursor, so `update` refuses to guess a
start date and requires an explicit `from` and `resolution`.

## Report

`report` reads the database only and never contacts the provider, so it is safe to run at any time.

```bash
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=report --axe-trader.history-import.epic=US500"
```

Omit the epic to report on every stored instrument, and pass `from`/`to` to restrict the range. It
reports stored minutes, excluded minutes, the resulting dirty rate, and the breakdown by failure
reason. A legacy database with no `price_exclusion` table reports zero exclusions rather than failing.

## Archive

`update` never rewrites `data/axe-trader.sqlite.gz` — re-gzipping a 281 MB database on every routine
append is disproportionate. Run `archive` before committing the snapshot, otherwise a fresh checkout
restores stale data through `DatabaseBootstrap`.

```bash
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=archive"
```

## Probe

The probe fetches and validates one short page without creating or changing the staging, active, or
archive files.

```bash
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=probe --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-02T15:00:00Z --axe-trader.history-import.to=2024-01-02T16:00:00Z --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"
```

Proceed only if the logged probe audit reconciles `received = accepted + rejected`, contains no
duplicates or continuity gaps, and reports `consistent=true`. Capital's `to` is inclusive, while the
importer's interval is half-open; the audit therefore excludes the nominal `to` candle rather than
recording it as an out-of-range rejection. Do not rely on Maven's exit status alone: DevTools can
surface an application-thread failure while Maven still prints `BUILD SUCCESS`; the audit line is
the required success signal.

## Stage

Capture one UTC upper bound once and reuse that exact value for the complete stage. Staging refuses
to overwrite an existing file.

The staging database must be on a local Unix filesystem that exposes Unix link attributes and
supports process-shared file locks. Do not hard-link a staging database: SQLite derives journal and
WAL names from the path, so multiple hard-link names can bypass its locking protocol and have
undefined behavior. Symlink aliases are supported because staging resolves them to the canonical
database path. A successful open also creates a stable sibling lease file named
`<staging-database>.stage.lock`; leave this file in place between runs so ownership handoffs cannot
split across different lock-file inodes.

```bash
IMPORT_END="$(date -u +%Y-%m-%dT%H:%M:00Z)"
printf 'IMPORT_END=%s\n' "$IMPORT_END"
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=stage --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z --axe-trader.history-import.to=${IMPORT_END} --axe-trader.history-import.staging-database=data/us500-clean-v2-stage.sqlite"
```

Do not promote unless the full staged audit is successful: counts reconcile, accepted rows equal
accepted minutes plus duplicates, duplicates are zero, accepted minutes are non-zero, continuity gaps
are zero, and `consistent=true`. Empty or 404 bounded provider windows are recorded as recognized
session closures; any missing minute inside a nonempty page is an unexplained continuity gap and blocks
promotion. Preserve the audit output with the fixed `IMPORT_END` in the operational record.

## Promote

Promotion re-audits the completed stage and verifies its stored audit fingerprint. It preserves the
old active database and archive as timestamped `.legacy-corrupt-<UTC timestamp>` backups, installs
the staged database as `data/axe-trader.sqlite`, rebuilds `data/axe-trader.sqlite.gz`, and removes
the staging file only after both replacements succeed.

```bash
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=promote --axe-trader.history-import.staging-database=data/us500-clean-v2-stage.sqlite"
```

After promotion, verify the active row count and UTC bounds, the gzip archive, both timestamped
legacy backups, and a local backtest before treating the local dataset as ready.

## 2026-08-02 execution record

The originally selected `[2024-01-01T00:00:00Z, 2024-01-01T01:00:00Z)` probe was a market-closure
window, not a provider outage. A read-only diagnostic on the open `[2024-01-02T15:00:00Z,
2024-01-02T16:00:00Z)` interval reached Capital; its 61st nominal upper-bound candle exposed the
provider's inclusive `to` convention. The importer now translates that boundary to its internal
half-open interval and records empty/404 bounded windows as recognized closures. The corrected
open-session probe then passed with 60 received/accepted, 0 rejected, 0 duplicates, 0 continuity
gaps, and `consistent=true`.

The complete stage used the fixed `IMPORT_END=2026-08-02T21:27:00Z` but stopped when Capital
returned HTTP 429 `error.too-many.requests`. No final staged audit was emitted and no completion
marker exists. The retained `data/us500-clean-stage.sqlite` contains 61 recorded page windows and
86 accepted US500/MINUTE rows, but is incomplete and must not be promoted or reused. No promotion,
backup creation, or post-promotion backtest was attempted. Resume only after the provider/paging
rate-limit behavior is corrected; do not bypass the stage/audit gate.

Before and after the probe, the active database SHA-256 was
`cb900eaf39050508ab51243faf5aa279971e1e5f1b8a2ad9795ed0ae777780df`, the archive SHA-256 was
`88b268f766c3ddb23601051ecfeaadfa04537fc228e1111e6429631db71ebb06`. The untouched active database
contained zero US500/MINUTE price rows and zero exclusions; the existing gzip archive passed
`gzip -t`. After the blocked stage, the partial staging database SHA-256 was
`ff0be34e11c0d8b5bcd589345bd59a1b2a45dcdfa49af46150f10c13c7191bbd`; it is retained solely as
failure evidence.

## 2026-08-03 paced v2 completion

The fresh `data/us500-clean-v2-stage.sqlite` import used
`IMPORT_END=2026-08-02T22:53:00Z`. Its completed audit had 913,053 received, 912,132 accepted,
921 rejected/excluded, 912,132 accepted minutes, zero duplicates, 12,780 recognized closures
(447,680 minutes), zero continuity gaps, zero pending work, and `consistent=true`. The staging
database stored one completion marker and fingerprint for the exact requested range.

Explicit promotion succeeded. The active database and decompressed archive both have SHA-256
`e19cbb90df9612df98283882aa9567efc335d60267baa90b71b402f1c30b9953`; the archive passed
`gzip -t`. The promoted active database contains 912,132 US500/MINUTE rows over
`[2024-01-01T23:01:00Z, 2026-08-02T22:52:00Z]` and 1,685 recorded exclusions. Legacy backups were
preserved as `axe-trader.sqlite.legacy-corrupt-20260803T003252Z` and
`axe-trader.sqlite.gz.legacy-corrupt-20260803T003252Z`. Focused history/import tests (82) and the
full Maven suite (233 tests, 3 skipped) passed after promotion.
