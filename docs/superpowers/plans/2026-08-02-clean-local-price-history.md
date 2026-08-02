# Clean Local Price History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `data/axe-trader.sqlite` from Capital minute data as a clean local research dataset, recording invalid source minutes as exclusions and making no D1 writes.

**Architecture:** A history package stages Capital minute pages in fresh SQLite. Valid candles go to `historical_price`; invalid minutes are preserved only in `price_exclusion`. Explicit audit/promotion atomically replaces the legacy-corrupt database. `BarSeriesFactory` remains responsible for rolling minute bars into any strategy timeframe and drops incomplete/excluded buckets.

**Tech Stack:** Java 21, Spring Boot 4, SQLite JDBC, existing Capital REST/auth client, Spring Data JPA, JUnit 5, AssertJ, Maven.

## Global Constraints

- `data/axe-trader.sqlite` is authoritative; never read/write D1 or `dashboard/`.
- Current SQLite and gzip snapshot are legacy-corrupt and must not seed the new dataset.
- Request US500 Capital `MINUTE` data from `2024-01-01T00:00:00Z` through a fixed run-start time, at 1,000 rows or fewer per request.
- Accept only unique, in-range, finite, positive bid/ask OHLC values where bid is less than or equal to ask at open/high/low/close.
- Never insert rejected rows into `historical_price`; record import id, source, epic, resolution, minute and failure reason in `price_exclusion`.
- A strategy-timeframe bucket is omitted when one expected minute is absent or excluded.
- Staging and audit never alter active files. Promotion backs up old `.sqlite` and `.gz`, atomically installs staging, and recreates gzip.
- Import code runs only with `axe-trader.history-import.enabled=true`.

---

### Task 1: Capital minute page source

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportRequest.java`
- Create: `src/main/java/io/g3tech/axetrader/history/ImportedPrice.java`
- Create: `src/main/java/io/g3tech/axetrader/history/ImportedPage.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoricalPricePageSource.java`
- Create: `src/main/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSource.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryImportRequestTest.java`
- Test: `src/test/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSourceTest.java`

**Interfaces:** Consumes `AuthenticationClient`, `ApiClient`, `ConversationContext`, `GetPricesRequest`, Capital DTOs. Produces `HistoricalPricePageSource.fetch(HistoryImportRequest, Instant, Instant, int)` and immutable page records.

- [ ] **Step 1: Write failing request and mapping tests**

```java
@Test
void requiresMinuteResolutionAndHalfOpenBounds() {
    assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
            "US500", "HOUR", Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-01-01T00:01:00Z"), staging, "capital"));
}

@Test
void preservesCapitalBidAskValues() {
    ImportedPage page = source.fetch(request, from, to, 1_000);
    assertThat(page.prices()).singleElement().satisfies(price -> {
        assertThat(price.timestamp()).isEqualTo(Instant.parse("2024-01-01T00:01:00Z"));
        assertThat(price.closeBid()).isEqualByComparingTo("4800.1");
        assertThat(price.closeAsk()).isEqualByComparingTo("4800.3");
    });
}
```

- [ ] **Step 2: Verify red**

Run `./mvnw test -Dtest=HistoryImportRequestTest,CapitalHistoricalPricePageSourceTest`.

Expected: FAIL because the history types are absent.

- [ ] **Step 3: Implement the request/page/source types**

```java
public interface HistoricalPricePageSource {
    ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive,
                       Instant toExclusive, int maxBars);
}

public record ImportedPage(Instant requestedFrom, Instant requestedTo,
                           List<ImportedPrice> prices, String payloadHash) {}
```

Require nonblank epic/source, a staging path, `MINUTE`, a valid half-open range, and page size 1–1,000. Lazily authenticate, map `snapshotTimeUTC` plus all bid/ask decimals without rounding, and SHA-256 hash canonical bounds and returned fields. Do not access JPA or D1.

- [ ] **Step 4: Verify green**

Run `./mvnw test -Dtest=HistoryImportRequestTest,CapitalHistoricalPricePageSourceTest`.

Expected: PASS.

- [ ] **Step 5: Commit** — add the Task 1 source/tests and commit `feat(history): add Capital minute page importer`.

### Task 2: Clean staging and exclusion audit

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/PriceValidationFailure.java`
- Create: `src/main/java/io/g3tech/axetrader/history/PriceValidator.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryStagingStore.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportAudit.java`
- Test: `src/test/java/io/g3tech/axetrader/history/PriceValidatorTest.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryStagingStoreTest.java`

**Interfaces:** Consumes `ImportedPage`/`ImportedPrice`; produces transactional `writePage`, SQL audit, compatible `historical_price`, `price_exclusion`, and `history_import_page` tables.

- [ ] **Step 1: Write failing validation and ledger tests**

```java
@Test
void rejectsCrossedCloseAndRecordsItsMinute() {
    store.writePage(page(price("2024-01-01T00:01:00Z", "4800.3", "4800.1")), request);
    assertThat(store.countAccepted(request)).isZero();
    assertThat(store.exclusions(request)).extracting(PriceExclusion::reason)
            .containsExactly("CLOSE_BID_ABOVE_ASK");
}

@Test
void rejectsNonPositiveValues() {
    assertThat(validator.validate(priceWithOpenBid(BigDecimal.ZERO)))
            .contains(PriceValidationFailure.OPEN_BID_NOT_POSITIVE);
}
```

- [ ] **Step 2: Verify red**

Run `./mvnw test -Dtest=PriceValidatorTest,HistoryStagingStoreTest`.

Expected: FAIL because validator/store types are absent.

- [ ] **Step 3: Implement validation and SQLite writes**

Create `historical_price` compatible with `V1__create_historical_price.sql`, including a unique index on `(source, epic, resolution, snapshot_time_utc)`. Create these ledger tables:

```sql
CREATE TABLE price_exclusion (
  import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
  snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
  PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason)
);
CREATE TABLE history_import_page (
  import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
  payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
  rejected_count INTEGER NOT NULL, PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc)
);
```

Validate before insertion. In one transaction per page insert UUID-backed valid rows, all failures for rejected rows, and page provenance. Duplicate pages are idempotent.

- [ ] **Step 4: Write an audit test, then implement SQL audit**

```java
@Test
void auditSeparatesAcceptedAndExcludedMinutes() {
    HistoryImportAudit audit = store.audit(request);
    assertThat(audit.acceptedMinuteCount()).isEqualTo(2);
    assertThat(audit.excludedMinuteCount()).isEqualTo(1);
    assertThat(audit.exclusionsByReason()).containsEntry("HIGH_BID_ABOVE_ASK", 1L);
}
```

First run `./mvnw test -Dtest=HistoryStagingStoreTest` and confirm it fails for missing `audit`. Implement requested/actual bounds, received/accepted/rejected counts, duplicate count, and exclusions grouped by reason. An audit fails only when an accepted row violates the invariant or a rejected row has no exclusion record.

- [ ] **Step 5: Verify green and commit**

Run `./mvnw test -Dtest=PriceValidatorTest,HistoryStagingStoreTest` (expected PASS), then commit `feat(history): stage clean minutes and exclusions`.

### Task 3: Timeframe-independent clean aggregation

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/strategy/backtest/repositories/data/PriceExclusion.java`
- Create: `src/main/java/io/g3tech/axetrader/strategy/backtest/repositories/PriceExclusionRepository.java`
- Modify: `src/main/java/io/g3tech/axetrader/backtest/series/BarSeriesFactory.java`
- Test: `src/test/java/io/g3tech/axetrader/backtest/series/BarSeriesFactoryTest.java`

**Interfaces:** Consumes active excluded minute timestamps and loaded prices. Produces `fromPricesWithSides(..., Set<Instant>)`, which never forms a bucket containing an excluded or missing minute.

- [ ] **Step 1: Write the failing exclusion test**

```java
@Test
void excludesAnyTimeframeBucketContainingAnExcludedMinute() {
    MarketSeries series = factory.fromPricesWithSides("US500", fiveMinutes(), 5,
            Set.of(Instant.parse("2024-01-01T00:03:00Z")));
    assertThat(series.mid().getBarCount()).isZero();
}
```

- [ ] **Step 2: Verify red**

Run `./mvnw test -Dtest=BarSeriesFactoryTest#excludesAnyTimeframeBucketContainingAnExcludedMinute`.

Expected: FAIL: the current method has no overload and creates the aggregate.

- [ ] **Step 3: Implement complete-bucket filtering**

Map the exclusion table read-only and query timestamps by epic/resolution/range. Partition input by requested UTC duration. Retain a bucket only when it contains all `timeframeMinutes` expected distinct minute endpoints and no excluded timestamp. Keep old overloads delegating with an empty set, then apply filtering before midpoint/bid/ask aggregation.

- [ ] **Step 4: Add the missing-minute regression test**

```java
@Test
void omitsFifteenMinuteBucketWhenOneMinuteIsMissing() {
    MarketSeries series = factory.fromPricesWithSides("US500", fourteenMinutes(), 15, Set.of());
    assertThat(series.mid().getBarCount()).isZero();
}
```

- [ ] **Step 5: Verify green and commit**

Run `./mvnw test -Dtest=BarSeriesFactoryTest,BacktestRunnerIntrabarTest` (expected PASS), then commit `feat(backtest): omit incomplete price buckets`.

### Task 4: Explicit local import workflow

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportProperties.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportService.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportRunner.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryDatabasePromoter.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryImportServiceTest.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryDatabasePromoterTest.java`

**Interfaces:** Consumes Tasks 1–2. Produces opt-in `probe`, `stage`, and `promote` modes and `promote(staging, active, archive, audit)`.

- [ ] **Step 1: Write failing isolation/promotion tests**

```java
@Test
void stageLeavesActiveDatabaseUnchanged() throws Exception {
    byte[] before = Files.readAllBytes(activeDatabase);
    service.stage(request);
    assertThat(Files.readAllBytes(activeDatabase)).isEqualTo(before);
    assertThat(stagingDatabase).exists();
}

@Test
void promotionBacksUpLegacyFilesAndRebuildsArchive() throws Exception {
    promoter.promote(stagingDatabase, activeDatabase, archive, promotableAudit);
    assertThat(archive).exists();
    assertThat(Files.list(activeDatabase.getParent()).map(Path::toString))
            .anyMatch(name -> name.contains("legacy-corrupt"));
}
```

- [ ] **Step 2: Verify red**

Run `./mvnw test -Dtest=HistoryImportServiceTest,HistoryDatabasePromoterTest`.

Expected: FAIL because service/promoter types are absent.

- [ ] **Step 3: Implement modes and safe promotion**

Add `axe-trader.history-import.enabled=false`, `mode`, `epic`, `resolution`, `from`, `to`, `staging-database`, `active-database=data/axe-trader.sqlite`, and `archive=data/axe-trader.sqlite.gz`. `probe` fetches/validates only. `stage` creates only the named staging file, pages non-overlapping half-open ranges, moves to greatest returned timestamp plus one minute, and fails on an empty/non-advancing page. `promote` re-audits staging.

Before promotion copy active `.sqlite` and `.gz` to `legacy-corrupt-<UTC timestamp>` siblings. Atomically move staged DB to active, with replace fallback only after backups. Write gzip to a temporary sibling then atomically move it to archive. No pre-move failure may alter active files.

- [ ] **Step 4: Verify green and commit**

Run `./mvnw test -Dtest=HistoryImportServiceTest,HistoryDatabasePromoterTest,HistoryStagingStoreTest,DatabaseBootstrapTest` (expected PASS), then commit `feat(history): add local clean-data import command`.

### Task 5: Runbook and local-only verification

**Files:**

- Modify: `docs/dev-environment.md`
- Modify: `TODO.md`
- Modify: `docs/d1-price-history-import-progress.md`

**Interfaces:** Consumes Task 4 modes. Produces an operational procedure and an explicit record that D1 price history is retired.

- [ ] **Step 1: Document exact local command forms**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=probe --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z --axe-trader.history-import.to=2024-01-01T01:00:00Z --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"

IMPORT_END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
./mvnw spring-boot:run -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=stage --axe-trader.history-import.epic=US500 --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z --axe-trader.history-import.to=${IMPORT_END} --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"

./mvnw spring-boot:run -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=promote --axe-trader.history-import.staging-database=data/us500-clean-stage.sqlite"
```

- [ ] **Step 2: Probe, stage, audit, then promote**

Record Capital probe received/accepted/rejected counts; choose and record a single fixed full-import end time. Inspect full audit prior to promotion. Verify promoted bounds/counts, archive, backups, and a local backtest. Do not run Wrangler, dashboard, or D1 commands.

- [ ] **Step 3: Final verification and commit**

Run `./mvnw test`, `git diff --check`, and `git status --short`. Expected: PASS, local SQLite artifacts ignored, no D1 write. Mark the D1 price-history workflow retired in its progress document, record audit evidence in `TODO.md`, and commit `docs(history): record clean local history workflow`.
