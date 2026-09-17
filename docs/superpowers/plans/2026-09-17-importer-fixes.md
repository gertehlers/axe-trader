# Importer Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Capital.com history importer trustworthy for 15 instruments: neutral labelling of empty intervals, safe seeding of new instruments, clean weekend top-ups, revised-bar pickup, session-expiry recovery and resumable updates.

**Architecture:** Small, test-first changes inside the existing Java `io.g3tech.axetrader.history` package (plus one `ApiClient` method and one Flyway migration). No change to the stage → audit → merge transaction model. Classification of market closures is deliberately removed from the importer and moves to the Python verification report (separate plan).

**Tech Stack:** Java 21, Spring Boot 4, Maven wrapper, JUnit 5 + AssertJ, SQLite (xerial sqlite-jdbc), Flyway.

**Spec:** `docs/superpowers/specs/2026-09-17-research-restart-design.md` §2.1 (I1–I7).

## Global Constraints

- Capital.com **demo API only**: `https://demo-api-capital.backend-capital.com` (spec D9).
- Canonical timestamp text for every writer: `YYYY-MM-DDTHH:MM:SSZ` (spec I6).
- Empty-interval provenance values are exactly: `EMPTY_BASE`, `EMPTY_REFETCH`, `NOT_FOUND_BASE`, `NOT_FOUND_REFETCH` (spec I1).
- Update window ends **2 minutes** before now and re-fetches the last **60** stored minutes (spec I4).
- On 401/403: create a new session **once** and retry (spec I5).
- History-start probe: weekly **Wednesday 12:00 UTC** 999-minute windows; seed from the **Monday 00:00 UTC** of the first week with data that is followed by a week with data; never before the configured start (spec I2).
- Bar timestamps are the bar's **open** minute in UTC (spec I7).
- Do not run imports against `data/axe-trader.sqlite` while another import process is running (SQLite single writer).
- Test command used throughout (from the worktree root): `./mvnw -q test -Dtest='Capital*Test,History*Test,Flyway*Test,InstantConverterTest'`

---

### Task 0: Workspace

**Files:** none (git only)

- [ ] **Step 1: Create the branch and worktree from the importer branch**

The delta-import code lives on `feature/delta-price-import` (not yet on `main`). Branch from it:

```bash
cd /Users/gertehlers/Development/projects/axe-trader
git worktree add .worktrees/research-restart -b feature/research-restart feature/delta-price-import
cd .worktrees/research-restart
```

- [ ] **Step 2: Bring the spec, learnings and plans onto the branch**

```bash
git log --oneline worktree-sr-bounce-test -6   # find the docs(learnings), docs(spec) x2 and docs(plans) commits
git cherry-pick <learnings-sha> <spec-sha> <spec-update-sha> <plans-sha>
```

Expected: clean cherry-picks (they only add files under `docs/`).

- [ ] **Step 3: Baseline test run**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: BUILD SUCCESS. If anything fails before any change, stop and report it.

---

### Task 1: Flyway V2 — unique index, ledger tables, canonical timestamps (I6)

**Files:**
- Create: `src/main/resources/db/migration/V2__history_integrity.sql`
- Modify: `src/main/java/io/g3tech/axetrader/strategy/backtest/repositories/InstantConverter.java`
- Test: `src/test/java/io/g3tech/axetrader/history/FlywayHistoryIntegrityMigrationTest.java`
- Test: `src/test/java/io/g3tech/axetrader/strategy/backtest/repositories/InstantConverterTest.java`

**Interfaces:**
- Produces: Flyway version `2` creating index `historical_price_source_epic_resolution_timestamp` and tables `history_import_run`, `price_exclusion`, `history_import_page`, `history_import_closure` (same DDL as `HistoryDeltaMerger.LEDGER_SCHEMA`).

Note: import mode disables Flyway, so the live history DB keeps getting this schema from `HistoryDeltaMerger.prepareTarget` (unchanged). V2 protects fresh databases and any JPA writer.

- [ ] **Step 1: Write the failing migration test**

```java
package io.g3tech.axetrader.history;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayHistoryIntegrityMigrationTest {

    @TempDir
    Path directory;

    @Test
    void normalisesShortTimestampsAndEnforcesOneRowPerMinute() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("migrate.sqlite");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("1").load().migrate();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().execute("""
                    INSERT INTO historical_price VALUES
                      ('a','US500','MINUTE','2024-12-04T23:20Z',1,1,1,1,1,1,1,1,1,'capital','2024-12-04T23:21:00Z')
                    """);
        }

        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            var rows = connection.createStatement().executeQuery("SELECT snapshot_time_utc FROM historical_price");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("2024-12-04T23:20:00Z");
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO historical_price VALUES
                      ('b','US500','MINUTE','2024-12-04T23:20:00Z',1,1,1,1,1,1,1,1,1,'capital','2024-12-04T23:21:00Z')
                    """)).isInstanceOf(SQLException.class);
            var tables = connection.createStatement().executeQuery("""
                    SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN
                      ('history_import_run','price_exclusion','history_import_page','history_import_closure')
                    """);
            assertThat(tables.getLong(1)).isEqualTo(4);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=FlywayHistoryIntegrityMigrationTest`
Expected: FAIL — timestamp is still `2024-12-04T23:20Z` and the duplicate insert succeeds.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V2__history_integrity.sql`:

```sql
UPDATE historical_price
SET snapshot_time_utc = substr(snapshot_time_utc, 1, 16) || ':00Z'
WHERE length(snapshot_time_utc) = 17 AND substr(snapshot_time_utc, 17, 1) = 'Z';

CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
ON historical_price (source, epic, resolution, snapshot_time_utc);

CREATE TABLE IF NOT EXISTS history_import_run (
  import_run_id TEXT PRIMARY KEY, algorithm_version INTEGER NOT NULL, source TEXT NOT NULL,
  epic TEXT NOT NULL, resolution TEXT NOT NULL, requested_from_utc TEXT NOT NULL,
  requested_to_utc TEXT NOT NULL, created_at_utc TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS price_exclusion (
  import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
  snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
  PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason),
  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id));

CREATE INDEX IF NOT EXISTS price_exclusion_epic_resolution_timestamp
ON price_exclusion (epic, resolution, snapshot_time_utc);

CREATE TABLE IF NOT EXISTS history_import_page (
  import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
  payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
  rejected_count INTEGER NOT NULL,
  PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc),
  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id));

CREATE TABLE IF NOT EXISTS history_import_closure (
  import_run_id TEXT NOT NULL, from_utc TEXT NOT NULL, to_utc TEXT NOT NULL,
  provenance TEXT NOT NULL, payload_hash TEXT NOT NULL,
  PRIMARY KEY (import_run_id, from_utc, to_utc),
  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id));
```

- [ ] **Step 4: Run the migration test**

Run: `./mvnw -q test -Dtest=FlywayHistoryIntegrityMigrationTest`
Expected: PASS.

- [ ] **Step 5: Write the failing converter test**

```java
package io.g3tech.axetrader.strategy.backtest.repositories;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InstantConverterTest {

    private final InstantConverter converter = new InstantConverter();

    @Test
    void writesTheCanonicalSecondsFormat() {
        assertThat(converter.convertToDatabaseColumn(Instant.parse("2024-12-04T23:20:00Z")))
                .isEqualTo("2024-12-04T23:20:00Z");
    }

    @Test
    void readsBothTheCanonicalAndTheLegacyShortFormat() {
        assertThat(converter.convertToEntityAttribute("2024-12-04T23:20:00Z"))
                .isEqualTo(Instant.parse("2024-12-04T23:20:00Z"));
        assertThat(converter.convertToEntityAttribute("2024-12-04T23:20Z"))
                .isEqualTo(Instant.parse("2024-12-04T23:20:00Z"));
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=InstantConverterTest`
Expected: FAIL on `writesTheCanonicalSecondsFormat` (actual `2024-12-04T23:20Z`).

- [ ] **Step 7: Fix the converter**

Replace `convertToDatabaseColumn` in `InstantConverter.java`:

```java
    private static final java.time.format.DateTimeFormatter CANONICAL =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return CANONICAL.format(attribute);
    }
```

Keep `convertToEntityAttribute` as is (`ZonedDateTime.parse` accepts both forms).

- [ ] **Step 8: Run both tests and the baseline suite**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test,Flyway*Test,InstantConverterTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V2__history_integrity.sql \
  src/main/java/io/g3tech/axetrader/strategy/backtest/repositories/InstantConverter.java \
  src/test/java/io/g3tech/axetrader/history/FlywayHistoryIntegrityMigrationTest.java \
  src/test/java/io/g3tech/axetrader/strategy/backtest/repositories/InstantConverterTest.java
git commit -m "fix(history): enforce one row per minute and one timestamp format in Flyway"
```

---

### Task 2: Neutral provenance for empty intervals (I1)

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/ImportedPage.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSource.java:94-97`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryImportService.java:139`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryStagingStore.java` (ALGORITHM_VERSION, WorkItem, nextPending, initializeRun, enqueueMissingRuns, insertWork, insertClosure, ledgerCoverage, applySchema)
- Modify (rename only): `HistoryImportAudit.java`, `HistoryCoverage.java`, `HistoryImportRunner.java`, `HistoryUpdateService.java`, `HistoryImportService.java`, `HistoryImportServiceTest.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryImportServiceTest.java`, `CapitalHistoricalPricePageSourceTest.java`

**Interfaces:**
- Produces: `ImportedPage(Instant requestedFrom, Instant requestedTo, List<ImportedPrice> prices, String payloadHash, boolean providerNotFound)` plus the existing 4-arg constructor (defaults `providerNotFound=false`).
- Produces: `HistoryImportAudit.providerEmptyIntervals()` (renamed from `recognizedSessionClosures()`) and `providerEmptyMinuteCount()` (renamed from `recognizedClosureMinuteCount()`); `HistoryCoverage.Assessment.providerEmptyIntervals()`.
- Produces: `HistoryStagingStore.ALGORITHM_VERSION == 4`; `WorkItem(long id, String importRunId, Instant fromInclusive, Instant toExclusive, String origin)` with origin `BASE` or `REFETCH`.

- [ ] **Step 1: Mechanical rename (no behaviour change)**

```bash
grep -rl 'recognizedSessionClosures\|recognizedClosureMinuteCount' src \
  | xargs sed -i '' -e 's/recognizedSessionClosures/providerEmptyIntervals/g' \
                    -e 's/recognizedClosureMinuteCount/providerEmptyMinuteCount/g'
sed -i '' -e 's/closures={} ({} minutes)/providerEmpty={} ({} minutes)/' \
  src/main/java/io/g3tech/axetrader/history/HistoryImportRunner.java
sed -i '' -e 's/closures={}, rowsMerged/providerEmpty={}, rowsMerged/' \
  src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java
```

Add this Javadoc above the `providerEmptyIntervals` component's first use in `HistoryImportAudit` (record header comment):

```java
/**
 * Audit of one staged import.
 *
 * <p>{@code providerEmptyIntervals} are intervals for which Capital.com returned no bars (empty body or
 * 404). They are <strong>not</strong> verified market closures — the provider response cannot tell a
 * closure from a data hole. Closure classification happens in the Python data verification report
 * using Capital.com trading hours.
 */
```

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS (pure rename).

- [ ] **Step 2: Commit the rename**

```bash
git add -A src
git commit -m "refactor(history): call empty provider intervals what they are, not closures"
```

- [ ] **Step 3: Write the failing tests**

In `CapitalHistoricalPricePageSourceTest`, extend the existing not-found test and add a normal-page assertion:

```java
    @Test
    void translatesCapitalNotFoundForAClosedWindowToAnEmptyPage() {
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(
                new StubAuthenticationClient(), new StubApiClient(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "closed", HttpHeaders.EMPTY, new byte[0], null)));

        ImportedPage page = source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(page.prices()).isEmpty();
        assertThat(page.providerNotFound()).isTrue();
    }

    @Test
    void marksAnOrdinaryResponseAsFound() {
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(
                new StubAuthenticationClient(), new StubApiClient(response()));

        assertThat(source.fetch(REQUEST, FROM, TO, 1_000).providerNotFound()).isFalse();
    }
```

In `HistoryImportServiceTest`, change line 192's expected provenance from `"CAPITAL_EMPTY_OR_404"` to `"EMPTY_REFETCH"`, and add:

```java
    @Test
    void labelsEmptyIntervalsByWorkOriginAndProviderAnswer() {
        Instant firstWindowEnd = FROM.plusSeconds(999 * 60L);
        Instant end = FROM.plusSeconds(1_000 * 60L);
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, end,
                tempDir.resolve("labels-stage.sqlite"), "capital");
        ScriptedSource source = new ScriptedSource(Map.of(
                new Interval(FROM, firstWindowEnd), page(FROM, firstWindowEnd, price("2024-01-01T00:00:00Z")),
                new Interval(FROM.plusSeconds(60), firstWindowEnd),
                new ImportedPage(FROM.plusSeconds(60), firstWindowEnd, List.of(), "not-found-refetch", true),
                new Interval(firstWindowEnd, end), page(firstWindowEnd, end)));

        HistoryImportAudit audit = new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());

        assertThat(audit.providerEmptyIntervals())
                .extracting(HistoryCoverageGap::provenance)
                .containsExactlyInAnyOrder("NOT_FOUND_REFETCH", "EMPTY_BASE");
        assertThat(audit.isConsistent()).isTrue();
    }
```

- [ ] **Step 4: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='CapitalHistoricalPricePageSourceTest,HistoryImportServiceTest'`
Expected: compilation failure (`providerNotFound()` / 5-arg `ImportedPage` do not exist).

- [ ] **Step 5: Implement `ImportedPage.providerNotFound`**

Replace `ImportedPage.java` body:

```java
public record ImportedPage(
        Instant requestedFrom,
        Instant requestedTo,
        List<ImportedPrice> prices,
        String payloadHash,
        boolean providerNotFound
) {

    public ImportedPage {
        if (requestedFrom == null || requestedTo == null || !requestedFrom.isBefore(requestedTo)) {
            throw new IllegalArgumentException("requestedFrom must be before requestedTo");
        }
        prices = List.copyOf(prices);
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("payloadHash must be configured");
        }
        if (providerNotFound && !prices.isEmpty()) {
            throw new IllegalArgumentException("A not-found page cannot carry prices");
        }
    }

    public ImportedPage(Instant requestedFrom, Instant requestedTo, List<ImportedPrice> prices, String payloadHash) {
        this(requestedFrom, requestedTo, prices, payloadHash, false);
    }
}
```

In `CapitalHistoricalPricePageSource.fetch`, replace the `response == null` branch:

```java
        if (response == null) {
            return new ImportedPage(fromInclusive, toExclusive, List.of(),
                    hashPage(fromInclusive, toExclusive, List.of()), true);
        }
```

In `HistoryImportService.fetch` (last line), carry the flag:

```java
        return new ImportedPage(fromInclusive, toExclusive, normalized, page.payloadHash(), page.providerNotFound());
```

- [ ] **Step 6: Implement work origin and provenance in `HistoryStagingStore`**

1. `static final int ALGORITHM_VERSION = 4;`
2. In `applySchema`, the `history_import_work` DDL gains a column after `state`:

```sql
                      origin TEXT NOT NULL DEFAULT 'BASE' CHECK (origin IN ('BASE','REFETCH')),
```

3. `record WorkItem(long id, String importRunId, Instant fromInclusive, Instant toExclusive, String origin) { }`
4. `nextPending` selects `work_id, requested_from_utc, requested_to_utc, origin` and builds `new WorkItem(rows.getLong(1), stageRunId, Instant.parse(rows.getString(2)), Instant.parse(rows.getString(3)), rows.getString(4))`.
5. `insertWork` gets an `origin` parameter:

```java
    private void insertWork(HistoryImportRequest request, Instant from, Instant to, String origin) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO history_import_work (
                    import_run_id, requested_from_utc, requested_to_utc, state, origin)
                VALUES (?, ?, ?, 'PENDING', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            statement.setString(2, from.toString());
            statement.setString(3, to.toString());
            statement.setString(4, origin);
            statement.executeUpdate();
        }
    }
```

   `initializeRun` calls `insertWork(request, from, to, "BASE")`; `enqueueMissingRuns` calls `insertWork(..., "REFETCH")` (both call sites).
6. `process(...)`: replace `insertClosure(work, request, page.payloadHash());` with `insertClosure(work, request, page);` and rewrite `insertClosure`:

```java
    private void insertClosure(WorkItem work, HistoryImportRequest request, ImportedPage page) throws SQLException {
        String provenance = (page.providerNotFound() ? "NOT_FOUND_" : "EMPTY_") + work.origin();
        String sql = """
                INSERT OR IGNORE INTO history_import_closure (
                    import_run_id, from_utc, to_utc, provenance, payload_hash)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            statement.setString(2, work.fromInclusive().toString());
            statement.setString(3, work.toExclusive().toString());
            statement.setString(4, provenance);
            statement.setString(5, page.payloadHash());
            statement.executeUpdate();
        }
    }
```

7. In `ledgerCoverage`, replace the provenance check:

```java
                        if (!EMPTY_PROVENANCES.contains(provenance)) {
                            throw new IllegalStateException("Unsupported closure provenance: " + provenance);
                        }
```

   with the constant near the top of the class:

```java
    static final Set<String> EMPTY_PROVENANCES =
            Set.of("EMPTY_BASE", "EMPTY_REFETCH", "NOT_FOUND_BASE", "NOT_FOUND_REFETCH");
```

8. `grep -n "WorkItem(" src/test` — update any test that constructs `WorkItem` directly by adding `"BASE"` as the last argument. Afterwards `grep -rn "CAPITAL_EMPTY_OR_404" src` must print nothing; tamper tests that write a provenance string should use `'EMPTY_BASE'` where they need a valid value.

- [ ] **Step 7: Run the tests**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS, including `labelsEmptyIntervalsByWorkOriginAndProviderAnswer`. `resumeRefusesMismatchedIdentityAndUnknownAlgorithmVersionBeforeFetching` should still pass (it writes an unknown version); if it hardcodes `3` as the *valid* version, change that to `HistoryStagingStore.ALGORITHM_VERSION`.

- [ ] **Step 8: Commit**

```bash
git add -A src
git commit -m "fix(history): label empty provider intervals by origin and answer instead of calling them closures"
```

---

### Task 3: Refuse to seed an epic Capital.com does not know (I2, part 1)

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/history/InstrumentCatalog.java`
- Modify: `src/main/java/io/g3tech/axetrader/brokers/capital/ApiClient.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSource.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateConfiguration.java`
- Test: `src/test/java/io/g3tech/axetrader/history/CapitalInstrumentCatalogTest.java`, `HistoryUpdateServiceTest.java`

**Interfaces:**
- Produces: `interface InstrumentCatalog { boolean exists(String epic); }`
- Produces: `ApiClient.getMarketDetails(ConversationContext, String epic) : GetMarketDetailsResponse`
- Produces: `HistoryUpdateService(HistoryCursorReader, HistoryImportService, HistoryDeltaMerger, InstrumentCatalog, HistoryStartProbe, Path stagingDirectory, Supplier<Instant> clock)` — note `HistoryStartProbe` is created in Task 4; in this task add the `InstrumentCatalog` parameter only (6-arg), Task 4 adds the probe.

- [ ] **Step 1: Write the failing catalog test**

```java
package io.g3tech.axetrader.history;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.ConversationContext;
import io.g3tech.axetrader.brokers.capital.domain.CapitalUserConfig;
import io.g3tech.axetrader.brokers.capital.dto.market.details.GetMarketDetailsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalInstrumentCatalogTest {

    @Test
    void knowsAnEpicWithMarketDetails() {
        var source = new CapitalHistoricalPricePageSource(new Auth(), new MarketsApi(null));
        assertThat(source.exists("OIL_BRENT")).isTrue();
    }

    @Test
    void doesNotKnowAnEpicCapitalAnswersNotFoundFor() {
        var notFound = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "error.not-found.epic",
                HttpHeaders.EMPTY, new byte[0], null);
        var source = new CapitalHistoricalPricePageSource(new Auth(), new MarketsApi(notFound));
        assertThat(source.exists("UKOIL")).isFalse();
    }

    private static final class Auth extends AuthenticationClient {
        Auth() {
            super("http://localhost", new CapitalUserConfig("login", "password", "api-key"));
        }

        @Override
        public ConversationContext createSession() {
            return new ConversationContext("client-token", "account-token", "wss://streaming.example");
        }
    }

    private static final class MarketsApi extends ApiClient {
        private final RuntimeException failure;

        MarketsApi(RuntimeException failure) {
            super("http://localhost");
            this.failure = failure;
        }

        @Override
        public GetMarketDetailsResponse getMarketDetails(ConversationContext context, String epic) {
            if (failure != null) {
                throw failure;
            }
            return new GetMarketDetailsResponse(null, null, null);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=CapitalInstrumentCatalogTest`
Expected: compilation failure (`getMarketDetails`, `exists` missing).

- [ ] **Step 3: Implement**

`InstrumentCatalog.java`:

```java
package io.g3tech.axetrader.history;

/** Answers whether the broker knows an instrument, so a typo never seeds years of "empty" history. */
@FunctionalInterface
public interface InstrumentCatalog {
    boolean exists(String epic);
}
```

`ApiClient.java` — add import `io.g3tech.axetrader.brokers.capital.dto.market.details.GetMarketDetailsResponse`, static import `API_V1_MARKETS`, and:

```java
    public GetMarketDetailsResponse getMarketDetails(ConversationContext conversationContext, String epic) {
        var requestHeaders = buildHttpHeadersForContext(conversationContext);

        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path(API_V1_MARKETS.value()).pathSegment(epic).build())
                .headers(httpHeaders -> httpHeaders.addAll(requestHeaders))
                .retrieve()
                .body(GetMarketDetailsResponse.class);
    }
```

`CapitalHistoricalPricePageSource` — declare `implements HistoricalPricePageSource, InstrumentCatalog` and add:

```java
    @Override
    public boolean exists(String epic) {
        Objects.requireNonNull(epic, "epic");
        ConversationContext context = authenticatedContext();
        pacer.acquire();
        try {
            return apiClient.getMarketDetails(context, epic) != null;
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest unknown) {
            return false;
        }
    }
```

- [ ] **Step 4: Run the catalog test**

Run: `./mvnw -q test -Dtest=CapitalInstrumentCatalogTest`
Expected: PASS.

- [ ] **Step 5: Write the failing update-service test**

In `HistoryUpdateServiceTest.setUp`, construct the service with a catalog that rejects `NOPE`:

```java
        service = new HistoryUpdateService(new HistoryCursorReader(active), imports,
                new HistoryDeltaMerger(), epic -> !epic.equals("NOPE"), directory.resolve(".staging"),
                () -> Instant.parse("2026-08-06T09:14:00Z"));
```

Add:

```java
    @Test
    void refusesToSeedAnEpicCapitalDoesNotKnow() {
        List<HistoryUpdateOutcome> outcomes = service.update("NOPE", "MINUTE",
                Instant.parse("2024-01-01T00:00:00Z"), active, archive);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(HistoryUpdateOutcome.Status.FAILED);
            assertThat(outcome.failure()).contains("Unknown epic");
        });
        assertThat(imports.requests).isEmpty();
    }
```

- [ ] **Step 6: Run to verify it fails**

Run: `./mvnw -q test -Dtest=HistoryUpdateServiceTest`
Expected: compilation failure (constructor arity).

- [ ] **Step 7: Implement in `HistoryUpdateService`**

Add field `private final InstrumentCatalog catalog;`, constructor parameter after `merger` (`this.catalog = Objects.requireNonNull(catalog, "catalog");`). In `updateOne`, directly after the `window.isEmpty()` block:

```java
        if (cursor.isEmpty() && !catalog.exists(target.epic())) {
            logger.error("{} is not a Capital.com epic; refusing to seed it", target.epic());
            return HistoryUpdateOutcome.failed(target, null, null, "Unknown epic at Capital.com: " + target.epic());
        }
```

`HistoryUpdateConfiguration.historyUpdateService` gains parameter `CapitalHistoricalPricePageSource catalog` and passes it after `merger`.

- [ ] **Step 8: Run the suite**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A src
git commit -m "fix(history): refuse to seed an epic Capital.com does not know"
```

---

### Task 4: History-start probe for new instruments (I2, part 2)

**Files:**
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryStartProbe.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateConfiguration.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryStartProbeTest.java`, `HistoryUpdateServiceTest.java`

**Interfaces:**
- Consumes: `HistoricalPricePageSource.fetch(HistoryImportRequest, Instant, Instant, int)`.
- Produces: `HistoryStartProbe(HistoricalPricePageSource)`; `Optional<Instant> firstAvailableFrom(HistoryTarget target, Instant configuredFrom, Instant now)`.
- Produces: `HistoryUpdateService(HistoryCursorReader, HistoryImportService, HistoryDeltaMerger, InstrumentCatalog, HistoryStartProbe, Path, Supplier<Instant>)`.

- [ ] **Step 1: Write the failing probe test**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStartProbeTest {

    private static final HistoryTarget TARGET = new HistoryTarget("capital", "GOLD", "MINUTE");
    private static final Instant NOW = Instant.parse("2024-03-01T00:00:00Z");

    @Test
    void startsAtTheMondayOfTheFirstWeekWithDataFollowedByAnotherWeekWithData() {
        // Wednesdays 2024-01-03 (no data), 01-10 (data), 01-17 (no data: holiday-like), 01-24 (data), 01-31 (data)
        FakeSource source = new FakeSource(Set.of(
                Instant.parse("2024-01-10T12:00:00Z"),
                Instant.parse("2024-01-24T12:00:00Z"),
                Instant.parse("2024-01-31T12:00:00Z")));

        assertThat(new HistoryStartProbe(source).firstAvailableFrom(TARGET, Instant.parse("2024-01-01T00:00:00Z"), NOW))
                .contains(Instant.parse("2024-01-22T00:00:00Z"));
    }

    @Test
    void neverStartsBeforeTheConfiguredStart() {
        FakeSource source = new FakeSource(Set.of(
                Instant.parse("2024-01-03T12:00:00Z"), Instant.parse("2024-01-10T12:00:00Z")));

        assertThat(new HistoryStartProbe(source).firstAvailableFrom(TARGET, Instant.parse("2024-01-02T05:00:00Z"), NOW))
                .contains(Instant.parse("2024-01-02T05:00:00Z"));
    }

    @Test
    void reportsNoHistoryWhenNoWeekHasData() {
        FakeSource source = new FakeSource(Set.of());

        assertThat(new HistoryStartProbe(source).firstAvailableFrom(TARGET, Instant.parse("2024-01-01T00:00:00Z"), NOW))
                .isEmpty();
        assertThat(source.probedFrom).first().isEqualTo(Instant.parse("2024-01-03T12:00:00Z"));
    }

    private static final class FakeSource implements HistoricalPricePageSource {
        private final Set<Instant> withData;
        private final List<Instant> probedFrom = new ArrayList<>();

        FakeSource(Set<Instant> withData) {
            this.withData = withData;
        }

        @Override
        public ImportedPage fetch(HistoryImportRequest request, Instant from, Instant to, int maxBars) {
            probedFrom.add(from);
            List<ImportedPrice> prices = withData.contains(from)
                    ? List.of(new ImportedPrice(from, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                    java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                    java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, 1L))
                    : List.of();
            return new ImportedPage(from, to, prices, "probe-" + from);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=HistoryStartProbeTest`
Expected: compilation failure (`HistoryStartProbe` missing).

- [ ] **Step 3: Implement the probe**

```java
package io.g3tech.axetrader.history;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds where an instrument's minute history really starts, so a seed never records months of
 * "provider empty" intervals before the instrument existed.
 *
 * <p>Probes one 999-minute window starting Wednesday 12:00 UTC per week (every market in scope is open
 * then). The seed starts at the Monday 00:00 UTC of the first week that has data and is followed by a
 * week with data — a single empty week (holiday) never ends the search, and a single stray week never
 * starts it.
 */
public final class HistoryStartProbe {

    static final Duration PROBE_WINDOW = Duration.ofMinutes(999);
    private static final Path UNUSED_STAGING = Path.of("probe-only-no-staging.sqlite");

    private final HistoricalPricePageSource pageSource;

    public HistoryStartProbe(HistoricalPricePageSource pageSource) {
        this.pageSource = Objects.requireNonNull(pageSource, "pageSource");
    }

    public Optional<Instant> firstAvailableFrom(HistoryTarget target, Instant configuredFrom, Instant now) {
        Instant candidate = null;
        for (Instant monday = mondayOf(configuredFrom); !probeStart(monday).plus(PROBE_WINDOW).isAfter(now);
             monday = monday.plus(Duration.ofDays(7))) {
            boolean hasData = hasData(target, probeStart(monday));
            if (hasData && candidate != null) {
                return Optional.of(later(candidate, configuredFrom));
            }
            candidate = hasData ? monday : null;
        }
        return Optional.ofNullable(candidate).map(monday -> later(monday, configuredFrom));
    }

    private boolean hasData(HistoryTarget target, Instant from) {
        Instant to = from.plus(PROBE_WINDOW);
        HistoryImportRequest probe = new HistoryImportRequest(target.epic(), target.resolution(), from, to,
                UNUSED_STAGING, target.source());
        return !pageSource.fetch(probe, from, to, 1_000).prices().isEmpty();
    }

    static Instant mondayOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant probeStart(Instant monday) {
        return monday.plus(Duration.ofDays(2)).plus(Duration.ofHours(12));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
```

- [ ] **Step 4: Run the probe test**

Run: `./mvnw -q test -Dtest=HistoryStartProbeTest`
Expected: PASS.

- [ ] **Step 5: Write the failing update-service test**

In `HistoryUpdateServiceTest`, add a field `private Instant probeStart = Instant.parse("2024-02-05T00:00:00Z");` and build the service in `setUp` with a probe whose source answers from that field:

```java
        HistoryStartProbe probe = new HistoryStartProbe((request, from, to, maxBars) ->
                new ImportedPage(from, to, from.isBefore(probeStart) ? List.of()
                        : List.of(new ImportedPrice(from, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, 1L)),
                        "probe-" + from));
        service = new HistoryUpdateService(new HistoryCursorReader(active), imports,
                new HistoryDeltaMerger(), epic -> !epic.equals("NOPE"), probe, directory.resolve(".staging"),
                () -> Instant.parse("2026-08-06T09:14:00Z"));
```

Add:

```java
    @Test
    void seedsANewInstrumentFromWhereItsHistoryReallyStarts() {
        service.update("SILVER", "MINUTE", Instant.parse("2024-01-01T00:00:00Z"), active, archive);

        assertThat(imports.requests).singleElement()
                .extracting(HistoryImportRequest::from)
                .isEqualTo(Instant.parse("2024-02-05T00:00:00Z"));
    }

    @Test
    void failsASeedWhenCapitalHasNoHistoryAtAll() {
        probeStart = Instant.parse("2030-01-01T00:00:00Z");

        List<HistoryUpdateOutcome> outcomes = service.update("SILVER", "MINUTE",
                Instant.parse("2024-01-01T00:00:00Z"), active, archive);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(HistoryUpdateOutcome.Status.FAILED);
            assertThat(outcome.failure()).contains("No history");
        });
    }
```

- [ ] **Step 6: Run to verify it fails**

Run: `./mvnw -q test -Dtest=HistoryUpdateServiceTest`
Expected: compilation failure (constructor arity).

- [ ] **Step 7: Implement in `HistoryUpdateService`**

Add field `private final HistoryStartProbe startProbe;` and constructor parameter after `catalog`. In `updateOne`, after the catalog check, replace `Instant from = window.get().fromInclusive();` with:

```java
        Instant from = window.get().fromInclusive();
        if (cursor.isEmpty()) {
            Optional<Instant> start = startProbe.firstAvailableFrom(target, from, now);
            if (start.isEmpty() || !start.get().isBefore(window.get().toExclusive())) {
                return HistoryUpdateOutcome.failed(target, from, window.get().toExclusive(),
                        "No history available at Capital.com from " + from);
            }
            from = start.get();
        }
```

`HistoryUpdateConfiguration.historyUpdateService` passes `new HistoryStartProbe(catalog)` after `catalog`.

- [ ] **Step 8: Run the suite**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A src
git commit -m "fix(history): seed new instruments from where their history really starts"
```

---

### Task 5: A top-up with nothing new is "already current" (I3)

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryDatabasePromoter.java:110-118`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryImportService.java:54-81`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java`
- Test: `HistoryImportServiceTest.java`, `HistoryUpdateServiceTest.java`

**Interfaces:**
- Produces: `HistoryDatabasePromoter.requireConsistentAudit(HistoryImportAudit)` (all checks of `requirePromotableAudit` except `acceptedMinuteCount() > 0`).
- Produces: `HistoryImportService.stage(HistoryImportRequest, Path activeDatabase, Path archive, boolean allowEmpty)`; the 3-arg `stage` delegates with `false`.

- [ ] **Step 1: Write the failing tests**

`HistoryImportServiceTest`:

```java
    @Test
    void anEmptyWindowStagesCleanlyWhenEmptyIsAllowed() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO)));

        HistoryImportAudit audit = new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request(), activeDatabase(), archive(), true);

        assertThat(audit.acceptedMinuteCount()).isZero();
        assertThat(audit.isConsistent()).isTrue();
    }

    @Test
    void anEmptyWindowIsStillRefusedForAFullImport() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO)));

        assertThatThrownBy(() -> new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to promote");
    }
```

`HistoryUpdateServiceTest` — change `RecordingImportService` to override the 4-arg `stage` and support an empty mode:

```java
        private String emptyFor;

        @Override
        public HistoryImportAudit stage(HistoryImportRequest request, Path activeDatabase, Path archive,
                                        boolean allowEmpty) {
            requests.add(request);
            if (request.epic().equals(failFor)) {
                throw new IllegalStateException("provider rejected the request");
            }
            HistoryStagingStoreFixtures.writeMinimalDelta(request);
            if (request.epic().equals(emptyFor)) {
                try {
                    Files.writeString(request.stagingDatabase().resolveSibling(
                            request.stagingDatabase().getFileName() + ".stage.lock"), "lease");
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
                return new HistoryImportAudit(request.from(), request.to(), null, null, 0, 0, 0, 0, 0, 0,
                        java.util.Map.of(), List.of(new HistoryCoverageGap(request.from(), request.to(),
                        "EMPTY_BASE", "h")), List.of(), true);
            }
            return HistoryStagingStoreFixtures.passingAudit(request);
        }
```

and add:

```java
    @Test
    void aWeekendTopUpWithNothingNewIsAlreadyCurrentAndLeavesNoStagingFiles() throws Exception {
        imports.emptyFor = "US500";

        List<HistoryUpdateOutcome> outcomes = service.update("US500", null, null, active, archive);

        assertThat(outcomes).singleElement().extracting(HistoryUpdateOutcome::status)
                .isEqualTo(HistoryUpdateOutcome.Status.ALREADY_CURRENT);
        try (var files = Files.list(directory.resolve(".staging"))) {
            assertThat(files).isEmpty();
        }
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest='HistoryImportServiceTest,HistoryUpdateServiceTest'`
Expected: compilation failure (4-arg `stage` missing).

- [ ] **Step 3: Implement**

`HistoryDatabasePromoter`:

```java
    static void requirePromotableAudit(HistoryImportAudit audit) {
        requireConsistentAudit(audit);
        if (audit.acceptedMinuteCount() == 0) {
            throw new IllegalStateException("Refusing to promote a failed history import audit");
        }
    }

    static void requireConsistentAudit(HistoryImportAudit audit) {
        Objects.requireNonNull(audit, "audit");
        boolean countsReconcile = audit.receivedCount() == audit.acceptedCount() + audit.rejectedCount();
        boolean acceptedRowsReconcile = audit.acceptedCount() == audit.acceptedMinuteCount() + audit.duplicateCount();
        if (!audit.isConsistent() || audit.duplicateCount() != 0 || !countsReconcile || !acceptedRowsReconcile
                || !audit.continuityGaps().isEmpty()) {
            throw new IllegalStateException("Refusing to promote a failed history import audit");
        }
    }
```

`HistoryImportService`: rename the body of the existing 3-arg `stage` into a 4-arg overload; replace `HistoryDatabasePromoter.requirePromotableAudit(audit);` with:

```java
        if (allowEmpty) {
            HistoryDatabasePromoter.requireConsistentAudit(audit);
        } else {
            HistoryDatabasePromoter.requirePromotableAudit(audit);
        }
```

and make the 3-arg overload `return stage(request, activeDatabase, archive, false);`.

`HistoryUpdateService.updateOne`: call `importService.stage(request, activeDatabase, archive, true)`; immediately after it:

```java
            if (audit.acceptedMinuteCount() == 0 && audit.excludedMinuteCount() == 0) {
                deleteStagingArtifacts(staging);
                logger.info("{} {} has no new minutes in [{}, {}); already current", target.epic(),
                        target.resolution(), from, to);
                return HistoryUpdateOutcome.alreadyCurrent(target);
            }
```

Rename `deleteQuietly` to `deleteStagingArtifacts` and make it also remove the lease file:

```java
    private static void deleteStagingArtifacts(Path staging) {
        for (Path file : List.of(staging, staging.resolveSibling(staging.getFileName() + ".stage.lock"))) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                logger.warn("Could not remove the staging file {}", file, exception);
            }
        }
    }
```

- [ ] **Step 4: Run the suite**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "fix(history): treat a top-up with no new minutes as already current and clean up its files"
```

---

### Task 6: Settle margin, overlap re-fetch and bar revisions (I4)

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateWindow.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryDeltaMerger.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java` (log line)
- Test: `HistoryUpdateWindowTest.java` (replace), `HistoryUpdateServiceTest.java`, `HistoryDeltaMergerTest.java`, new `HistoryDeltaMergerRevisionTest.java`

**Interfaces:**
- Produces: `HistoryUpdateWindow.SETTLE = Duration.ofMinutes(2)`, `HistoryUpdateWindow.REVISION_OVERLAP = Duration.ofMinutes(60)`.
- Produces: `HistoryDeltaMerger.MergeResult(long pricesMerged, long exclusionsMerged, long pricesRevised)`; table `price_revision(source, epic, resolution, snapshot_time_utc, old_values, new_values, revised_at_utc)`.

- [ ] **Step 1: Replace `HistoryUpdateWindowTest` with the new rules**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryUpdateWindowTest {

    @Test
    void refetchesTheLastHourAndStopsTwoMinutesShortOfNow() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-02T22:52:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:37.412Z")))
                .contains(new HistoryUpdateWindow.Window(
                        Instant.parse("2026-08-02T21:53:00Z"),
                        Instant.parse("2026-08-06T09:12:00Z")));
    }

    @Test
    void reportsAlreadyCurrentWhenNoNewMinuteCanHaveSettled() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-06T09:11:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:13:59Z")))
                .isEmpty();
    }

    @Test
    void reportsAlreadyCurrentWhenTheCursorIsAheadOfTheSettledMinute() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-06T09:20:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:00Z")))
                .isEmpty();
    }

    @Test
    void seedsANewInstrumentFromTheConfiguredStartWithoutOverlap() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.empty(),
                Optional.of(Instant.parse("2024-01-01T00:00:00Z")),
                Instant.parse("2026-08-06T09:14:37Z")))
                .contains(new HistoryUpdateWindow.Window(
                        Instant.parse("2024-01-01T00:00:00Z"),
                        Instant.parse("2026-08-06T09:12:00Z")));
    }

    @Test
    void prefersTheCursorOverAConfiguredStart() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-02T22:52:00Z")),
                Optional.of(Instant.parse("2024-01-01T00:00:00Z")),
                Instant.parse("2026-08-06T09:14:00Z")))
                .map(HistoryUpdateWindow.Window::fromInclusive)
                .contains(Instant.parse("2026-08-02T21:53:00Z"));
    }

    @Test
    void failsClosedWhenThereIsNoCursorAndNoConfiguredStart() {
        assertThatIllegalStateException()
                .isThrownBy(() -> HistoryUpdateWindow.resolve(
                        Optional.empty(), Optional.empty(), Instant.parse("2026-08-06T09:14:00Z")))
                .withMessageContaining("explicit from");
    }

    @Test
    void rejectsAConfiguredStartThatIsNotOnAWholeMinute() {
        assertThatIllegalStateException()
                .isThrownBy(() -> HistoryUpdateWindow.resolve(
                        Optional.empty(),
                        Optional.of(Instant.parse("2024-01-01T00:00:30Z")),
                        Instant.parse("2026-08-06T09:14:00Z")))
                .withMessageContaining("whole UTC minute");
    }
}
```

In `HistoryUpdateServiceTest.updatesEveryStoredInstrumentFromItsOwnCursor`, the expected `from` values become `2026-08-02T21:53:00Z` and `2026-08-05T09:01:00Z`, and `to` becomes `2026-08-06T09:12:00Z`. In `reportsAlreadyCurrentWithoutCallingTheProvider`, insert the cursor row at `2026-08-06T09:11:00Z` instead of `09:13`.

- [ ] **Step 2: Write the failing revision test**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryDeltaMergerRevisionTest {

    @TempDir
    Path directory;

    @Test
    void replacesARevisedBarAndRecordsTheRevision() throws Exception {
        Path active = database("active.sqlite", "run-a", "2026-08-06T09:00:00Z", 100.0);
        Path delta = database("delta.sqlite", "run-b", "2026-08-06T09:00:00Z", 101.5);

        HistoryDeltaMerger.MergeResult result = new HistoryDeltaMerger().merge(delta, active);

        assertThat(result.pricesRevised()).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active)) {
            var price = connection.createStatement().executeQuery(
                    "SELECT close_bid, COUNT(*) FROM historical_price");
            assertThat(price.getDouble(1)).isEqualTo(101.5);
            assertThat(price.getLong(2)).isEqualTo(1);
            var revision = connection.createStatement().executeQuery(
                    "SELECT snapshot_time_utc, old_values, new_values FROM price_revision");
            assertThat(revision.next()).isTrue();
            assertThat(revision.getString(1)).isEqualTo("2026-08-06T09:00:00Z");
            assertThat(revision.getString(2)).contains("100");
            assertThat(revision.getString(3)).contains("101.5");
        }
    }

    @Test
    void anIdenticalOverlapRecordsNoRevision() throws Exception {
        Path active = database("active.sqlite", "run-a", "2026-08-06T09:00:00Z", 100.0);
        Path delta = database("delta.sqlite", "run-b", "2026-08-06T09:00:00Z", 100.0);

        assertThat(new HistoryDeltaMerger().merge(delta, active).pricesRevised()).isZero();
    }

    private Path database(String name, String runId, String minute, double closeBid) throws Exception {
        Path path = directory.resolve(name);
        HistoryStagingStore.open(path).close();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            connection.createStatement().execute("INSERT INTO history_import_run VALUES ('" + runId
                    + "', 4, 'capital', 'OIL_BRENT', 'MINUTE', '2026-08-06T08:00:00Z', '2026-08-06T10:00:00Z', "
                    + "'2026-08-06T10:00:00Z')");
            connection.createStatement().execute("INSERT INTO historical_price VALUES ('" + runId + "-p', "
                    + "'OIL_BRENT', 'MINUTE', '" + minute + "', 100, 100.04, 100.2, 100.24, 99.9, 99.94, "
                    + closeBid + ", " + (closeBid + 0.04) + ", 10, 'capital', '2026-08-06T10:00:00Z')");
        }
        return path;
    }
}
```

In `HistoryDeltaMergerTest`, change `new HistoryDeltaMerger.MergeResult(2, 1)` to `(2, 1, 0)` and `(0, 0)` to `(0, 0, 0)`.

- [ ] **Step 3: Run to verify they fail**

Run: `./mvnw -q test -Dtest='HistoryUpdateWindowTest,HistoryDeltaMerger*Test,HistoryUpdateServiceTest'`
Expected: FAIL / compilation failure (3-arg `MergeResult`, window values).

- [ ] **Step 4: Implement the window**

Replace `HistoryUpdateWindow.resolve`:

```java
    static final Duration SETTLE = Duration.ofMinutes(2);
    static final Duration REVISION_OVERLAP = Duration.ofMinutes(60);

    public static Optional<Window> resolve(Optional<Instant> cursor, Optional<Instant> configuredFrom, Instant now) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(configuredFrom, "configuredFrom");
        Objects.requireNonNull(now, "now");

        Instant toExclusive = now.truncatedTo(ChronoUnit.MINUTES).minus(SETTLE);
        if (cursor.isPresent()) {
            Instant next = cursor.get().plus(1, ChronoUnit.MINUTES);
            if (!next.isBefore(toExclusive)) {
                return Optional.empty();
            }
            Instant fromInclusive = next.minus(REVISION_OVERLAP);
            requireWholeMinute(fromInclusive);
            return Optional.of(new Window(fromInclusive, toExclusive));
        }
        Instant fromInclusive = configuredFrom.orElseThrow(() -> new IllegalStateException(
                "Instrument has no stored history; seeding it requires an explicit from"));
        requireWholeMinute(fromInclusive);
        return fromInclusive.isBefore(toExclusive)
                ? Optional.of(new Window(fromInclusive, toExclusive))
                : Optional.empty();
    }
```

Add `import java.time.Duration;`.

- [ ] **Step 5: Implement revisions in `HistoryDeltaMerger`**

Append to `LEDGER_SCHEMA`:

```java
            """
            CREATE TABLE IF NOT EXISTS price_revision (
              source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
              snapshot_time_utc TEXT NOT NULL, old_values TEXT NOT NULL, new_values TEXT NOT NULL,
              revised_at_utc TEXT NOT NULL)
            """
```

In `mergeTables`, after the `history_import_run` insert and **before** the price insert:

```java
            String differs = """
                    (a.open_bid <> d.open_bid OR a.open_ask <> d.open_ask OR a.high_bid <> d.high_bid
                     OR a.high_ask <> d.high_ask OR a.low_bid <> d.low_bid OR a.low_ask <> d.low_ask
                     OR a.close_bid <> d.close_bid OR a.close_ask <> d.close_ask
                     OR a.last_traded_volume <> d.last_traded_volume)
                    """;
            long revised = statement.executeUpdate("""
                    INSERT INTO price_revision
                    SELECT a.source, a.epic, a.resolution, a.snapshot_time_utc,
                           json_array(a.open_bid, a.open_ask, a.high_bid, a.high_ask, a.low_bid, a.low_ask,
                                      a.close_bid, a.close_ask, a.last_traded_volume),
                           json_array(d.open_bid, d.open_ask, d.high_bid, d.high_ask, d.low_bid, d.low_ask,
                                      d.close_bid, d.close_ask, d.last_traded_volume),
                           strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
                    FROM historical_price a JOIN delta.historical_price d
                      ON a.source = d.source AND a.epic = d.epic AND a.resolution = d.resolution
                     AND a.snapshot_time_utc = d.snapshot_time_utc
                    WHERE """ + differs);
            statement.executeUpdate("""
                    UPDATE historical_price AS a
                    SET open_bid = d.open_bid, open_ask = d.open_ask, high_bid = d.high_bid, high_ask = d.high_ask,
                        low_bid = d.low_bid, low_ask = d.low_ask, close_bid = d.close_bid, close_ask = d.close_ask,
                        last_traded_volume = d.last_traded_volume, ingestion_time_utc = d.ingestion_time_utc
                    FROM delta.historical_price AS d
                    WHERE a.source = d.source AND a.epic = d.epic AND a.resolution = d.resolution
                      AND a.snapshot_time_utc = d.snapshot_time_utc AND """ + differs);
```

Return `new MergeResult(prices, exclusions, revised);` and change the record to `public record MergeResult(long pricesMerged, long exclusionsMerged, long pricesRevised) { }`.

In `HistoryUpdateService`'s merged log line add `revised={}` with `merge.pricesRevised()`.

- [ ] **Step 6: Run the suite**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "fix(history): settle two minutes, re-fetch the last hour and record revised bars"
```

---

### Task 7: Re-authenticate once on an expired session (I5, part 1)

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSource.java:111-137`
- Test: `src/test/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSourceTest.java`

**Interfaces:** none new.

- [ ] **Step 1: Write the failing tests**

Add to `CapitalHistoricalPricePageSourceTest` (uses the file's existing `FakeTime`, `SequenceApiClient`, `StubAuthenticationClient`, `response`, `priceWithTypedValues` helpers):

```java
    @Test
    void reauthenticatesOnceWhenTheSessionHasExpired() {
        FakeTime time = new FakeTime();
        StubAuthenticationClient authentication = new StubAuthenticationClient();
        SequenceApiClient api = new SequenceApiClient(time,
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "expired", HttpHeaders.EMPTY, new byte[0], null),
                response(priceWithTypedValues("4800.1", "4800.3")));
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(authentication, api,
                new CapitalRequestPacer(5, time, time), new CapitalSessionPacer(time, time), time,
                () -> Duration.ZERO, 1, time::instant);

        ImportedPage page = source.fetch(REQUEST, FROM, TO, 1_000);

        assertThat(page.prices()).hasSize(1);
        assertThat(authentication.createSessionCalls).isEqualTo(2);
    }

    @Test
    void aSecondExpiredSessionInOneFetchFails() {
        FakeTime time = new FakeTime();
        StubAuthenticationClient authentication = new StubAuthenticationClient();
        RuntimeException expired = HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "expired", HttpHeaders.EMPTY, new byte[0], null);
        SequenceApiClient api = new SequenceApiClient(time, expired, expired);
        CapitalHistoricalPricePageSource source = new CapitalHistoricalPricePageSource(authentication, api,
                new CapitalRequestPacer(5, time, time), new CapitalSessionPacer(time, time), time,
                () -> Duration.ZERO, 3, time::instant);

        assertThatThrownBy(() -> source.fetch(REQUEST, FROM, TO, 1_000)).isSameAs(expired);
        assertThat(authentication.createSessionCalls).isEqualTo(2);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=CapitalHistoricalPricePageSourceTest`
Expected: FAIL — the 401 is thrown straight through and only one session is created.

- [ ] **Step 3: Implement**

Replace `fetchWithRetry`'s loop in `CapitalHistoricalPricePageSource`:

```java
        RuntimeException lastFailure = null;
        boolean reauthenticated = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ConversationContext context = authenticatedContext();
            pacer.acquire();
            try {
                return Objects.requireNonNull(apiClient.getPrices(
                                context, new GetPricesRequest(request.epic(), request.resolution(),
                                        fromInclusive, toExclusive, maxBars)),
                        "Capital prices response was empty");
            } catch (HttpClientErrorException.NotFound ignored) {
                return null;
            } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden expired) {
                if (reauthenticated) {
                    throw expired;
                }
                reauthenticated = true;
                invalidateConversationContext();
                attempt--; // a session refresh does not consume a retry attempt
            } catch (RuntimeException failure) {
                if (!retryable(failure) || attempt == maxAttempts) {
                    throw failure;
                }
                lastFailure = failure;
                Duration delay = retryDelay(failure, attempt);
                if (delay.compareTo(Duration.ofMinutes(10)) >= 0) {
                    invalidateConversationContext();
                }
                sleeper.sleep(delay);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Capital retry loop made no attempt") : lastFailure;
```

- [ ] **Step 4: Run the suite**

Run: `./mvnw -q test -Dtest='Capital*Test,History*Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "fix(history): re-authenticate once when a Capital session expires mid-import"
```

---

### Task 8: Resume retained staging files on the next update (I5, part 2)

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryImportService.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java`
- Test: `HistoryImportServiceTest.java`, `HistoryUpdateServiceTest.java`

**Interfaces:**
- Produces: `HistoryImportService.RetainedStage(Path path, Kind kind, HistoryImportRequest request)` with `enum Kind { RESUMABLE, COMPLETED, INCOMPATIBLE }` (`request` is null for `INCOMPATIBLE`).
- Produces: `HistoryImportService.inspectRetainedStage(Path staging) : RetainedStage`.

- [ ] **Step 1: Write the failing inspection tests**

`HistoryImportServiceTest`:

```java
    @Test
    void aStageInterruptedByAPagingFailureIsResumable() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO, price("2024-01-01T00:00:30Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());
        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()));

        HistoryImportService.RetainedStage retained = service.inspectRetainedStage(request().stagingDatabase());

        assertThat(retained.kind()).isEqualTo(HistoryImportService.RetainedStage.Kind.RESUMABLE);
        assertThat(retained.request()).isEqualTo(request());
    }

    @Test
    void aStageFromAnOlderAlgorithmIsIncompatible() throws Exception {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO, price("2024-01-01T00:00:30Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());
        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request().stagingDatabase())) {
            connection.createStatement().execute("UPDATE history_import_run SET algorithm_version = 3");
        }

        assertThat(service.inspectRetainedStage(request().stagingDatabase()).kind())
                .isEqualTo(HistoryImportService.RetainedStage.Kind.INCOMPATIBLE);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=HistoryImportServiceTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `inspectRetainedStage`**

Add to `HistoryImportService` (imports `java.util.Optional` not needed):

```java
    public record RetainedStage(Path path, Kind kind, HistoryImportRequest request) {
        public enum Kind { RESUMABLE, COMPLETED, INCOMPATIBLE }
    }

    public RetainedStage inspectRetainedStage(Path staging) {
        String url = "jdbc:sqlite:file:" + staging.toAbsolutePath().normalize() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            boolean completed;
            try (var rows = statement.executeQuery("""
                    SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='history_import_completion'
                    """)) {
                completed = rows.getLong(1) > 0;
            }
            try (var rows = statement.executeQuery("""
                    SELECT algorithm_version, source, epic, resolution, requested_from_utc, requested_to_utc
                    FROM history_import_run
                    """)) {
                if (!rows.next() || rows.getInt(1) != HistoryStagingStore.ALGORITHM_VERSION) {
                    return new RetainedStage(staging, RetainedStage.Kind.INCOMPATIBLE, null);
                }
                HistoryImportRequest request = new HistoryImportRequest(rows.getString(3), rows.getString(4),
                        Instant.parse(rows.getString(5)), Instant.parse(rows.getString(6)), staging,
                        rows.getString(2));
                return new RetainedStage(staging,
                        completed ? RetainedStage.Kind.COMPLETED : RetainedStage.Kind.RESUMABLE, request);
            }
        } catch (SQLException | RuntimeException unreadable) {
            return new RetainedStage(staging, RetainedStage.Kind.INCOMPATIBLE, null);
        }
    }
```

Run: `./mvnw -q test -Dtest=HistoryImportServiceTest` → PASS.

- [ ] **Step 4: Write the failing update-service test**

In `HistoryUpdateServiceTest.RecordingImportService` add:

```java
        private final java.util.Map<Path, RetainedStage> retained = new java.util.HashMap<>();

        @Override
        public RetainedStage inspectRetainedStage(Path staging) {
            return retained.getOrDefault(staging, new RetainedStage(staging, RetainedStage.Kind.INCOMPATIBLE, null));
        }
```

Add tests:

```java
    @Test
    void resumesAnInterruptedTopUpBeforeStartingANewOne() throws Exception {
        Path old = directory.resolve(".staging").resolve("US500-MINUTE-interrupted.sqlite");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "stage");
        HistoryImportRequest stored = new HistoryImportRequest("US500", "MINUTE",
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), old, "capital");
        imports.retained.put(old, new HistoryImportService.RetainedStage(old,
                HistoryImportService.RetainedStage.Kind.RESUMABLE, stored));

        service.update("US500", null, null, active, archive);

        assertThat(imports.requests).first().isEqualTo(stored);
    }

    @Test
    void quarantinesAStagingFileItCannotResume() throws Exception {
        Path old = directory.resolve(".staging").resolve("US500-MINUTE-old-algorithm.sqlite");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "stage");

        service.update("US500", null, null, active, archive);

        assertThat(old).doesNotExist();
        assertThat(directory.resolve(".staging").resolve("incompatible").resolve(old.getFileName())).exists();
    }
```

The placeholder file must exist so the service finds it, but the recording `stage` then writes a real SQLite delta at the same path. Make that possible by adding `Files.deleteIfExists(database);` as the first statement inside the `try` block of `HistoryStagingStoreFixtures.writeMinimalDelta` (next to `Files.createDirectories`).

- [ ] **Step 5: Run to verify they fail**

Run: `./mvnw -q test -Dtest=HistoryUpdateServiceTest`
Expected: FAIL — retained files are ignored.

- [ ] **Step 6: Implement in `HistoryUpdateService`**

In `update(...)`, inside the target loop, before `updateOne`:

```java
        for (HistoryTarget target : targets) {
            outcomes.addAll(resumeRetained(target, activeDatabase, archive));
            outcomes.add(updateOne(target, configuredFrom, now, activeDatabase, archive));
        }
```

Add:

```java
    private List<HistoryUpdateOutcome> resumeRetained(HistoryTarget target, Path activeDatabase, Path archive) {
        if (!Files.isDirectory(stagingDirectory)) {
            return List.of();
        }
        String prefix = target.epic() + "-" + target.resolution() + "-";
        List<Path> retained;
        try (var files = Files.list(stagingDirectory)) {
            retained = files.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".sqlite"))
                    .sorted().toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not list the staging directory " + stagingDirectory, exception);
        }
        List<HistoryUpdateOutcome> outcomes = new ArrayList<>();
        for (Path staging : retained) {
            HistoryImportService.RetainedStage stage = importService.inspectRetainedStage(staging);
            try {
                switch (stage.kind()) {
                    case INCOMPATIBLE -> quarantine(staging);
                    case COMPLETED -> {
                        HistoryDeltaMerger.MergeResult merge = merger.merge(staging, activeDatabase);
                        deleteStagingArtifacts(staging);
                        outcomes.add(HistoryUpdateOutcome.merged(target, stage.request().from(),
                                stage.request().to(), null, merge));
                    }
                    case RESUMABLE -> {
                        HistoryImportAudit audit = importService.stage(stage.request(), activeDatabase, archive, true);
                        HistoryDeltaMerger.MergeResult merge = merger.merge(staging, activeDatabase);
                        deleteStagingArtifacts(staging);
                        outcomes.add(HistoryUpdateOutcome.merged(target, stage.request().from(),
                                stage.request().to(), audit, merge));
                    }
                }
            } catch (RuntimeException exception) {
                logger.error("{} {} could not resume {}", target.epic(), target.resolution(), staging, exception);
                outcomes.add(HistoryUpdateOutcome.failed(target, null, null, exception.getMessage()));
            }
        }
        return outcomes;
    }

    private void quarantine(Path staging) {
        Path directory = stagingDirectory.resolve("incompatible");
        try {
            Files.createDirectories(directory);
            Files.move(staging, directory.resolve(staging.getFileName()));
            Files.deleteIfExists(staging.resolveSibling(staging.getFileName() + ".stage.lock"));
            logger.warn("Moved staging file {} made by another importer version to {}", staging, directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not quarantine " + staging, exception);
        }
    }
```

- [ ] **Step 7: Run the full test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS (whole project, not just history).

- [ ] **Step 8: Commit**

```bash
git add -A src
git commit -m "fix(history): resume or quarantine retained staging files before a new top-up"
```

---

### Task 9: Document the behaviour and prove it against the real API (I7 + run)

**Files:**
- Modify: `docs/local-price-history.md`
- Modify: `src/main/java/io/g3tech/axetrader/history/ImportedPrice.java` (Javadoc only)

- [ ] **Step 1: Pin the bar-time convention in code**

Add above the `ImportedPrice` record:

```java
/**
 * One minute bar. {@code timestamp} is Capital.com's {@code snapshotTimeUTC}: the bar's <strong>open</strong>
 * minute in UTC. Verified 2026-09-17 on the demo API: {@code from=12:00, to=12:02} returned bars stamped
 * 12:00, 12:01 and 12:02 (to is inclusive), so a bar stamped 12:00 covers [12:00, 12:01).
 */
```

- [ ] **Step 2: Update `docs/local-price-history.md`**

Add a section "Behaviour after the 2026-09 importer fixes" with exactly these points:
- Empty intervals are stored in `history_import_closure` with provenance `EMPTY_BASE | EMPTY_REFETCH | NOT_FOUND_BASE | NOT_FOUND_REFETCH`; they are **not** market closures. Rows written before the fix carry `CAPITAL_EMPTY_OR_404`. Closure classification lives in `research/engine` verification.
- Seeding requires the epic to exist (`GET /markets/{epic}`) and starts at the history-start probe result.
- Updates end 2 minutes before now, re-fetch the last 60 stored minutes, and record changed bars in `price_revision`.
- A top-up with nothing new exits 0 as "already current" and leaves no staging files.
- An expired session is re-created once per fetch. Retained staging files for the target are resumed (or merged if already completed) before a new top-up; files from an older importer version move to `data/.staging/incompatible/`.
- Bar timestamps are the bar's open minute (evidence as in the `ImportedPrice` Javadoc).

- [ ] **Step 3: Commit**

```bash
git add docs/local-price-history.md src/main/java/io/g3tech/axetrader/history/ImportedPrice.java
git commit -m "docs(history): record importer behaviour after the 2026-09 fixes"
```

- [ ] **Step 4: Real-API check (after the seed queue has finished)**

Confirm no import is running: `pgrep -f "history-import.mode=update"` prints nothing. Then, from the worktree root:

```bash
DB=/Users/gertehlers/Development/projects/axe-trader/.worktrees/delta-price-import/data
./mvnw -q spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication \
  "-Dspring-boot.run.arguments=--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update --axe-trader.history-import.active-database=$DB/axe-trader.sqlite --axe-trader.history-import.archive=$DB/axe-trader.sqlite.gz --axe-trader.history-import.staging-directory=$DB/.staging"
```

Expected log: one `Update complete:` line with `0 failed`; every stored instrument either merged (with `revised=` present) or already current. Then:

```bash
sqlite3 -readonly $DB/axe-trader.sqlite "SELECT provenance, COUNT(*) FROM history_import_closure GROUP BY 1;"
ls $DB/.staging
```

Expected: new provenance values appear next to the legacy `CAPITAL_EMPTY_OR_404`; `.staging` holds only an `incompatible/` directory (the pre-fix US500 file from 2026-09-17 14:02 is quarantined there).

- [ ] **Step 5: Unknown-epic check**

Run the same command with `--axe-trader.history-import.epic=UKOIL --axe-trader.history-import.from=2024-01-01T00:00:00Z`.
Expected: `Unknown epic at Capital.com: UKOIL`, exit code 1, no staging file created.

- [ ] **Step 6: Push**

```bash
git push -u origin feature/research-restart
```
