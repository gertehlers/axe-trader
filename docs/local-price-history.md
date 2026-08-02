# Local price-history operations

Price history is owned by the local SQLite database and its committed gzip archive. The former
Cloudflare D1 price-history import workflow is retired: do not use Wrangler, the dashboard, or D1
to build or repair this dataset.

Run the commands from the repository root. The explicit Config Data import points at the main
checkout's credential file because linked worktrees do not contain a copy. Never print or copy the
file's values.

## Probe

The probe fetches and validates one short page without creating or changing the staging, active, or
archive files.

```bash
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=probe --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z --axe-trader.history-import.to=2024-01-01T01:00:00Z --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"
```

Proceed only if the logged probe audit reconciles `received = accepted + rejected`, contains no
duplicates, and reports `consistent=true`. Do not rely on Maven's exit status alone: DevTools can
surface an application-thread failure while Maven still prints `BUILD SUCCESS`; the audit line is
the required success signal.

## Stage

Capture one UTC upper bound once and reuse that exact value for the complete stage. Staging refuses
to overwrite an existing file.

```bash
IMPORT_END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'IMPORT_END=%s\n' "$IMPORT_END"
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=stage --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z --axe-trader.history-import.to=${IMPORT_END} --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"
```

Do not promote unless the full staged audit is successful: counts reconcile, accepted rows equal
accepted minutes plus duplicates, duplicates are zero, accepted minutes are non-zero, and
`consistent=true`. Preserve the audit output with the fixed `IMPORT_END` in the operational record.

## Promote

Promotion re-audits the completed stage and verifies its stored audit fingerprint. It preserves the
old active database and archive as timestamped `.legacy-corrupt-<UTC timestamp>` backups, installs
the staged database as `data/axe-trader.sqlite`, rebuilds `data/axe-trader.sqlite.gz`, and removes
the staging file only after both replacements succeed.

```bash
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=promote --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"
```

After promotion, verify the active row count and UTC bounds, the gzip archive, both timestamped
legacy backups, and a local backtest before treating the local dataset as ready.

## 2026-08-02 execution record

The required read-only probe for `[2024-01-01T00:00:00Z, 2024-01-01T01:00:00Z)` reached Capital
but the provider returned HTTP 404 with error code `error.prices.not-found`. No probe audit was
logged, so received, accepted, and rejected counts are unavailable. The fail-closed gate stopped
the workflow before an `IMPORT_END` was captured: no stage, full audit, promotion, backup creation,
or post-promotion backtest was attempted.

Before and after the probe, the active database SHA-256 was
`cb900eaf39050508ab51243faf5aa279971e1e5f1b8a2ad9795ed0ae777780df`, the archive SHA-256 was
`88b268f766c3ddb23601051ecfeaadfa04537fc228e1111e6429631db71ebb06`, and the staging database was
absent. The untouched active database contained zero US500/MINUTE price rows and zero exclusions;
the existing gzip archive passed `gzip -t`. Resume from the probe, not the stage, after the
provider can serve the specified probe interval.
