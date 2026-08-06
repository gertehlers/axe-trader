# Instrument-Agnostic Delta Price Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `update` mode that tops every stored instrument up from its own last stored bar to the last completed UTC minute, writing only validated candles into `data/axe-trader.sqlite` and reporting the invalid source data it rejected.

**Architecture:** A delta stages into a small temporary SQLite file through the existing `HistoryStagingStore` — inheriting validation, the exclusion ledger, page provenance, request pacing and resume-after-429 unchanged — is audited by the existing `HistoryImportAudit`, and only then merged into the active database by `ATTACH` plus `INSERT OR IGNORE` inside one transaction. The cursor is derived from `MAX(snapshot_time_utc)` rather than stored, so it cannot drift and an interrupted run self-heals.

**Tech Stack:** Java 21, Spring Boot 4, SQLite JDBC, JUnit 5, AssertJ, Maven.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-06-instrument-agnostic-delta-import-design.md`.
- Never modify the existing `probe` / `stage` / `promote` paths. This work is additive.
- `data/axe-trader.sqlite` is authoritative and may hold many instruments. Never touch D1, Wrangler, or `dashboard/`.
- The active database is read-only until an audit passes. All active-database writes happen in exactly one transaction.
- Windows are half-open `[from, to)` and aligned to whole UTC minutes. `to` is `now` truncated to the minute, so the in-progress minute is never stored.
- Dirty candles never enter `historical_price`; they are recorded in `price_exclusion` with a reason. Dirty candles do not block a merge. Duplicates, counts that fail to reconcile, and unexplained continuity gaps do.
- An instrument with no stored rows requires an explicit `from` and `resolution`; without them the run fails closed.
- `INSERT OR IGNORE` is only safe when the unique index `historical_price_source_epic_resolution_timestamp` exists on the target. Assert it.
- Every task ends green and committed. Push after every commit.
- Run the full suite with `./mvnw test`. On a fresh container run `gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite` first.

---

### Task 1: Instrument target and cursor reader

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryTarget.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryCursorReader.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryCursorReaderTest.java`

**Interfaces:**

- Consumes: nothing from earlier tasks. Reads the active SQLite database directly with JDBC.
- Produces: `HistoryTarget(String source, String epic, String resolution)`; `HistoryCursorReader(Path activeDatabase)` with `List<HistoryTarget> storedTargets()` and `Optional<Instant> lastStoredMinute(HistoryTarget target)`.

- [ ] **Step 1: Write the failing tests**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryCursorReaderTest {

    @TempDir
    Path directory;

    private Path database;
    private HistoryCursorReader reader;

    @BeforeEach
    void setUp() throws Exception {
        database = directory.resolve("active.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
                      snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
                      high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL,
                      low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
                      last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp)
                    """);
        }
        reader = new HistoryCursorReader(database);
    }

    private void insert(String source, String epic, String resolution, String timestamp) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO historical_price VALUES ('" + java.util.UUID.randomUUID() + "', '"
                    + epic + "', '" + resolution + "', '" + timestamp
                    + "', 1,1,1,1,1,1,1,1, 10, '" + source + "', '2026-08-06T00:00:00Z')");
        }
    }

    @Test
    void reportsNoTargetsForAnEmptyTable() {
        assertThat(reader.storedTargets()).isEmpty();
    }

    @Test
    void listsEveryDistinctSourceEpicAndResolution() throws Exception {
        insert("capital", "US500", "MINUTE", "2026-08-02T22:50:00Z");
        insert("capital", "US500", "MINUTE", "2026-08-02T22:51:00Z");
        insert("capital", "GOLD", "MINUTE", "2026-08-02T22:51:00Z");

        assertThat(reader.storedTargets()).containsExactlyInAnyOrder(
                new HistoryTarget("capital", "US500", "MINUTE"),
                new HistoryTarget("capital", "GOLD", "MINUTE"));
    }

    @Test
    void resolvesTheLatestStoredMinutePerTarget() throws Exception {
        insert("capital", "US500", "MINUTE", "2026-08-02T22:50:00Z");
        insert("capital", "US500", "MINUTE", "2026-08-02T22:52:00Z");
        insert("capital", "GOLD", "MINUTE", "2026-08-01T10:00:00Z");

        assertThat(reader.lastStoredMinute(new HistoryTarget("capital", "US500", "MINUTE")))
                .contains(Instant.parse("2026-08-02T22:52:00Z"));
        assertThat(reader.lastStoredMinute(new HistoryTarget("capital", "GOLD", "MINUTE")))
                .contains(Instant.parse("2026-08-01T10:00:00Z"));
    }

    @Test
    void reportsNoCursorForAnUnknownTarget() {
        assertThat(reader.lastStoredMinute(new HistoryTarget("capital", "NASDAQ", "MINUTE")))
                .isEqualTo(Optional.empty());
    }

    @Test
    void failsClosedOnALegacyTimestampFormat() throws Exception {
        insert("capital", "US500", "MINUTE", "2024-12-04T23:20Z");

        assertThatIllegalStateException()
                .isThrownBy(() -> reader.lastStoredMinute(new HistoryTarget("capital", "US500", "MINUTE")))
                .withMessageContaining("2024-12-04T23:20Z");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=HistoryCursorReaderTest`

Expected: FAIL — `HistoryTarget` and `HistoryCursorReader` do not exist, so compilation fails.

- [ ] **Step 3: Implement the target record**

```java
package io.g3tech.axetrader.history;

public record HistoryTarget(String source, String epic, String resolution) {

    public HistoryTarget {
        requireNonBlank(source, "source");
        requireNonBlank(epic, "epic");
        requireNonBlank(resolution, "resolution");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
    }
}
```

- [ ] **Step 4: Implement the cursor reader**

The legacy dataset stored minutes as `2024-12-04T23:20Z`, which sorts differently from the clean
`2024-12-04T23:20:00Z` form. `MAX()` on mixed formats would silently return the wrong minute, so parse
strictly and fail closed rather than guess.

```java
package io.g3tech.axetrader.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class HistoryCursorReader {

    private final Path activeDatabase;

    public HistoryCursorReader(Path activeDatabase) {
        this.activeDatabase = Objects.requireNonNull(activeDatabase, "activeDatabase")
                .toAbsolutePath().normalize();
    }

    public List<HistoryTarget> storedTargets() {
        String sql = """
                SELECT DISTINCT source, epic, resolution FROM historical_price
                WHERE source IS NOT NULL AND epic IS NOT NULL AND resolution IS NOT NULL
                ORDER BY source, epic, resolution
                """;
        List<HistoryTarget> targets = new ArrayList<>();
        try (Connection connection = openReadOnly();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                targets.add(new HistoryTarget(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read stored instruments from " + activeDatabase, exception);
        }
        return List.copyOf(targets);
    }

    public Optional<Instant> lastStoredMinute(HistoryTarget target) {
        Objects.requireNonNull(target, "target");
        String sql = """
                SELECT MAX(snapshot_time_utc) FROM historical_price
                WHERE source = ? AND epic = ? AND resolution = ?
                """;
        try (Connection connection = openReadOnly();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.source());
            statement.setString(2, target.epic());
            statement.setString(3, target.resolution());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String value = rows.getString(1);
                return value == null ? Optional.empty() : Optional.of(parseStrictly(value));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read the stored cursor for " + target, exception);
        }
    }

    private static Instant parseStrictly(String value) {
        Instant parsed;
        try {
            parsed = Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                    "Stored minute is not an ISO-8601 instant and cannot be ordered safely: " + value, exception);
        }
        if (!parsed.toString().equals(value)) {
            throw new IllegalStateException(
                    "Stored minute is not in canonical ISO-8601 form and cannot be ordered safely: " + value);
        }
        return parsed;
    }

    private Connection openReadOnly() throws SQLException {
        if (!Files.isRegularFile(activeDatabase)) {
            throw new IllegalStateException("Active database does not exist: " + activeDatabase);
        }
        return DriverManager.getConnection("jdbc:sqlite:file:" + activeDatabase + "?mode=ro");
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryCursorReaderTest`

Expected: PASS, 5 tests.

Note: `Instant.parse("2024-12-04T23:20Z")` succeeds but re-serialises as `2024-12-04T23:20:00Z`, which
is why the canonical-form check is needed to make `failsClosedOnALegacyTimestampFormat` pass.

- [ ] **Step 6: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/HistoryTarget.java \
        src/main/java/io/g3tech/axetrader/history/HistoryCursorReader.java \
        src/test/java/io/g3tech/axetrader/history/HistoryCursorReaderTest.java
git commit -m "feat(history): read stored instruments and cursors"
git push
```

---

### Task 2: Update window resolution

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateWindow.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryUpdateWindowTest.java`

**Interfaces:**

- Consumes: nothing. Pure time arithmetic.
- Produces: `HistoryUpdateWindow.Window(Instant fromInclusive, Instant toExclusive)` and the static `Optional<Window> resolve(Optional<Instant> cursor, Optional<Instant> configuredFrom, Instant now)`. `Optional.empty()` means already current.

- [ ] **Step 1: Write the failing tests**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryUpdateWindowTest {

    @Test
    void startsOneMinuteAfterTheCursorAndExcludesTheInProgressMinute() {
        Optional<HistoryUpdateWindow.Window> window = HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-02T22:52:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:37.412Z"));

        assertThat(window).contains(new HistoryUpdateWindow.Window(
                Instant.parse("2026-08-02T22:53:00Z"),
                Instant.parse("2026-08-06T09:14:00Z")));
    }

    @Test
    void reportsAlreadyCurrentWhenTheCursorIsTheLastCompletedMinute() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-06T09:13:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:00Z")))
                .isEmpty();
    }

    @Test
    void reportsAlreadyCurrentWhenTheCursorIsAheadOfTheLastCompletedMinute() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-06T09:20:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:00Z")))
                .isEmpty();
    }

    @Test
    void seedsANewInstrumentFromTheConfiguredStart() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.empty(),
                Optional.of(Instant.parse("2024-01-01T00:00:00Z")),
                Instant.parse("2026-08-06T09:14:37Z")))
                .contains(new HistoryUpdateWindow.Window(
                        Instant.parse("2024-01-01T00:00:00Z"),
                        Instant.parse("2026-08-06T09:14:00Z")));
    }

    @Test
    void prefersTheCursorOverAConfiguredStart() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-02T22:52:00Z")),
                Optional.of(Instant.parse("2024-01-01T00:00:00Z")),
                Instant.parse("2026-08-06T09:14:00Z")))
                .map(HistoryUpdateWindow.Window::fromInclusive)
                .contains(Instant.parse("2026-08-02T22:53:00Z"));
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

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=HistoryUpdateWindowTest`

Expected: FAIL — `HistoryUpdateWindow` does not exist.

- [ ] **Step 3: Implement the resolver**

```java
package io.g3tech.axetrader.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

public final class HistoryUpdateWindow {

    private HistoryUpdateWindow() {
    }

    public static Optional<Window> resolve(Optional<Instant> cursor, Optional<Instant> configuredFrom, Instant now) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(configuredFrom, "configuredFrom");
        Objects.requireNonNull(now, "now");

        Instant toExclusive = now.truncatedTo(ChronoUnit.MINUTES);
        Instant fromInclusive = cursor
                .map(minute -> minute.plus(1, ChronoUnit.MINUTES))
                .or(() -> configuredFrom)
                .orElseThrow(() -> new IllegalStateException(
                        "Instrument has no stored history; seeding it requires an explicit from"));
        requireWholeMinute(fromInclusive);

        return fromInclusive.isBefore(toExclusive)
                ? Optional.of(new Window(fromInclusive, toExclusive))
                : Optional.empty();
    }

    private static void requireWholeMinute(Instant instant) {
        if (instant.getNano() != 0 || Math.floorMod(instant.getEpochSecond(), 60) != 0) {
            throw new IllegalStateException("Import bounds must fall on a whole UTC minute: " + instant);
        }
    }

    public record Window(Instant fromInclusive, Instant toExclusive) {

        public Window {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("fromInclusive must be before toExclusive");
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryUpdateWindowTest`

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/HistoryUpdateWindow.java \
        src/test/java/io/g3tech/axetrader/history/HistoryUpdateWindowTest.java
git commit -m "feat(history): resolve incremental update windows"
git push
```

---

### Task 3: Transactional delta merge

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryDeltaMerger.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryDeltaMergerTest.java`

**Interfaces:**

- Consumes: a completed, audited staging database written by `HistoryStagingStore`.
- Produces: `HistoryDeltaMerger.merge(Path stagingDatabase, Path activeDatabase)` returning `MergeResult(long pricesMerged, long exclusionsMerged)`.

The merger must prepare the target before merging. A legacy active database has `historical_price`
from Flyway `V1` but no unique index and no ledger tables — and without the unique index
`INSERT OR IGNORE` inserts duplicates instead of ignoring them, which would silently corrupt the
dataset. Creating the index on a table that already contains duplicates fails, which is the correct
fail-closed signal.

Insert order matters: `history_import_run` is the foreign-key parent of the page, closure and
exclusion tables.

- [ ] **Step 1: Write the failing tests**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryDeltaMergerTest {

    private static final String PRICE_TABLE = """
            CREATE TABLE historical_price (
              id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
              snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
              high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL,
              low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
              last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp)
            """;

    @TempDir
    Path directory;

    private Path active;
    private Path staging;
    private HistoryDeltaMerger merger;

    @BeforeEach
    void setUp() throws Exception {
        active = directory.resolve("active.sqlite");
        staging = directory.resolve("delta.sqlite");
        merger = new HistoryDeltaMerger();
        execute(active, PRICE_TABLE);
        createStagingSchema(staging);
    }

    private static void execute(Path database, String... statements) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void createStagingSchema(Path database) throws Exception {
        execute(database, PRICE_TABLE,
                """
                CREATE UNIQUE INDEX historical_price_source_epic_resolution_timestamp
                ON historical_price (source, epic, resolution, snapshot_time_utc)
                """,
                """
                CREATE TABLE history_import_run (
                  import_run_id TEXT PRIMARY KEY, algorithm_version INTEGER NOT NULL, source TEXT NOT NULL,
                  epic TEXT NOT NULL, resolution TEXT NOT NULL, requested_from_utc TEXT NOT NULL,
                  requested_to_utc TEXT NOT NULL, created_at_utc TEXT NOT NULL)
                """,
                """
                CREATE TABLE price_exclusion (
                  import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
                  snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
                  PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason),
                  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                """,
                """
                CREATE TABLE history_import_page (
                  import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
                  payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
                  rejected_count INTEGER NOT NULL,
                  PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc),
                  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                """,
                """
                CREATE TABLE history_import_closure (
                  import_run_id TEXT NOT NULL, from_utc TEXT NOT NULL, to_utc TEXT NOT NULL,
                  provenance TEXT NOT NULL, payload_hash TEXT NOT NULL,
                  PRIMARY KEY (import_run_id, from_utc, to_utc),
                  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                """,
                """
                INSERT INTO history_import_run VALUES
                  ('run-1', 3, 'capital', 'US500', 'MINUTE',
                   '2026-08-02T22:53:00Z', '2026-08-06T09:14:00Z', '2026-08-06T09:14:05Z')
                """,
                """
                INSERT INTO historical_price VALUES
                  ('p1','US500','MINUTE','2026-08-02T22:53:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-06T09:14:05Z'),
                  ('p2','US500','MINUTE','2026-08-02T22:54:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-06T09:14:05Z')
                """,
                """
                INSERT INTO price_exclusion VALUES
                  ('run-1','capital','US500','MINUTE','2026-08-02T22:55:00Z',
                   'CLOSE_BID_ABOVE_ASK','2026-08-06T09:14:05Z')
                """);
    }

    private static long count(Path database, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void mergesPricesAndExclusionsIntoALedgerlessActiveDatabase() throws Exception {
        HistoryDeltaMerger.MergeResult result = merger.merge(staging, active);

        assertThat(result).isEqualTo(new HistoryDeltaMerger.MergeResult(2, 1));
        assertThat(count(active, "historical_price")).isEqualTo(2);
        assertThat(count(active, "price_exclusion")).isEqualTo(1);
        assertThat(count(active, "history_import_run")).isEqualTo(1);
    }

    @Test
    void isIdempotentWhenTheSameDeltaIsMergedTwice() throws Exception {
        merger.merge(staging, active);
        HistoryDeltaMerger.MergeResult second = merger.merge(staging, active);

        assertThat(second).isEqualTo(new HistoryDeltaMerger.MergeResult(0, 0));
        assertThat(count(active, "historical_price")).isEqualTo(2);
        assertThat(count(active, "price_exclusion")).isEqualTo(1);
    }

    @Test
    void leavesOtherInstrumentsUntouched() throws Exception {
        execute(active, """
                INSERT INTO historical_price VALUES
                  ('g1','GOLD','MINUTE','2026-08-02T22:53:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-01T00:00:00Z')
                """);

        merger.merge(staging, active);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM historical_price WHERE epic = 'GOLD'")) {
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void failsClosedWhenTheActiveDatabaseAlreadyContainsDuplicateMinutes() throws Exception {
        execute(active, """
                INSERT INTO historical_price VALUES
                  ('d1','US500','MINUTE','2026-08-01T00:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-01T00:00:00Z'),
                  ('d2','US500','MINUTE','2026-08-01T00:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-01T00:00:00Z')
                """);

        assertThatIllegalStateException()
                .isThrownBy(() -> merger.merge(staging, active))
                .withMessageContaining("duplicate");
    }

    @Test
    void leavesTheActiveDatabaseUnchangedWhenTheStagingDatabaseIsUnusable() throws Exception {
        Path broken = directory.resolve("broken.sqlite");
        execute(broken, PRICE_TABLE);

        assertThatIllegalStateException().isThrownBy(() -> merger.merge(broken, active));
        assertThat(count(active, "historical_price")).isZero();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=HistoryDeltaMergerTest`

Expected: FAIL — `HistoryDeltaMerger` does not exist.

- [ ] **Step 3: Implement the merger**

```java
package io.g3tech.axetrader.history;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

@Component
public class HistoryDeltaMerger {

    private static final List<String> LEDGER_SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS history_import_run (
              import_run_id TEXT PRIMARY KEY, algorithm_version INTEGER NOT NULL, source TEXT NOT NULL,
              epic TEXT NOT NULL, resolution TEXT NOT NULL, requested_from_utc TEXT NOT NULL,
              requested_to_utc TEXT NOT NULL, created_at_utc TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS price_exclusion (
              import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
              snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
              PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason),
              FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
            """,
            """
            CREATE INDEX IF NOT EXISTS price_exclusion_epic_resolution_timestamp
            ON price_exclusion (epic, resolution, snapshot_time_utc)
            """,
            """
            CREATE TABLE IF NOT EXISTS history_import_page (
              import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
              payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
              rejected_count INTEGER NOT NULL,
              PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc),
              FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
            """,
            """
            CREATE TABLE IF NOT EXISTS history_import_closure (
              import_run_id TEXT NOT NULL, from_utc TEXT NOT NULL, to_utc TEXT NOT NULL,
              provenance TEXT NOT NULL, payload_hash TEXT NOT NULL,
              PRIMARY KEY (import_run_id, from_utc, to_utc),
              FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
            """);

    private static final String PRICE_COLUMNS = """
            id, epic, resolution, snapshot_time_utc, open_bid, open_ask, high_bid, high_ask,
            low_bid, low_ask, close_bid, close_ask, last_traded_volume, source, ingestion_time_utc
            """;

    public MergeResult merge(Path stagingDatabase, Path activeDatabase) {
        Path staging = requireRegularFile(stagingDatabase, "Staging database");
        Path active = requireRegularFile(activeDatabase, "Active database");
        if (staging.equals(active)) {
            throw new IllegalArgumentException("Staging and active paths must be different");
        }
        requireStagedDelta(staging);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active)) {
            connection.setAutoCommit(false);
            try {
                prepareTarget(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ATTACH DATABASE '" + staging.toString().replace("'", "''") + "' AS delta");
                }
                long prices;
                long exclusions;
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT OR IGNORE INTO history_import_run
                            SELECT import_run_id, algorithm_version, source, epic, resolution,
                                   requested_from_utc, requested_to_utc, created_at_utc
                            FROM delta.history_import_run
                            """);
                    prices = statement.executeUpdate(
                            "INSERT OR IGNORE INTO historical_price (" + PRICE_COLUMNS + ") SELECT "
                                    + PRICE_COLUMNS + " FROM delta.historical_price");
                    exclusions = statement.executeUpdate("""
                            INSERT OR IGNORE INTO price_exclusion
                            SELECT import_run_id, source, epic, resolution, snapshot_time_utc, reason, detected_at_utc
                            FROM delta.price_exclusion
                            """);
                    statement.executeUpdate("""
                            INSERT OR IGNORE INTO history_import_page
                            SELECT import_run_id, requested_from_utc, requested_to_utc, payload_hash,
                                   received_count, accepted_count, rejected_count
                            FROM delta.history_import_page
                            """);
                    statement.executeUpdate("""
                            INSERT OR IGNORE INTO history_import_closure
                            SELECT import_run_id, from_utc, to_utc, provenance, payload_hash
                            FROM delta.history_import_closure
                            """);
                }
                connection.commit();
                return new MergeResult(prices, exclusions);
            } catch (SQLException | RuntimeException failure) {
                rollbackQuietly(connection, failure);
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("Could not merge the staged delta into " + active, failure);
            } finally {
                detachQuietly(connection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open the active database " + active, exception);
        }
    }

    private static void prepareTarget(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : LEDGER_SCHEMA) {
                statement.execute(sql);
            }
            try {
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
                        ON historical_price (source, epic, resolution, snapshot_time_utc)
                        """);
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Active database contains duplicate minutes and cannot accept a merge until they are resolved",
                        exception);
            }
        }
    }

    private static void requireStagedDelta(Path staging) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + staging + "?mode=ro");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'history_import_run'")) {
            rows.next();
            if (rows.getLong(1) == 0) {
                throw new IllegalStateException("Staging database is not a staged history import: " + staging);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read the staged delta " + staging, exception);
        }
    }

    private static Path requireRegularFile(Path path, String description) {
        Path normalized = Objects.requireNonNull(path, description).toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalStateException(description + " does not exist: " + normalized);
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            failure.addSuppressed(exception);
        }
    }

    private static void detachQuietly(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DETACH DATABASE delta");
        } catch (SQLException ignored) {
            // The delta was never attached, or the connection is already closing.
        }
    }

    public record MergeResult(long pricesMerged, long exclusionsMerged) {
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryDeltaMergerTest`

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/HistoryDeltaMerger.java \
        src/test/java/io/g3tech/axetrader/history/HistoryDeltaMergerTest.java
git commit -m "feat(history): merge audited deltas into the active database"
git push
```

---

### Task 4: Update orchestration

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateOutcome.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryUpdateServiceTest.java`

**Interfaces:**

- Consumes: `HistoryCursorReader`, `HistoryUpdateWindow`, `HistoryDeltaMerger`, and the existing `HistoryImportService.stage(HistoryImportRequest, Path, Path)`, `HistoryImportRequest`, `HistoryImportAudit`.
- Produces: `HistoryUpdateService.update(String epic, String resolution, Instant configuredFrom, Path activeDatabase, Path archive)` returning `List<HistoryUpdateOutcome>`; `HistoryUpdateOutcome(HistoryTarget target, Status status, Instant fromInclusive, Instant toExclusive, HistoryImportAudit audit, HistoryDeltaMerger.MergeResult merge, String failure)` with `Status` in `{ALREADY_CURRENT, MERGED, FAILED}`.

Staging files live under `data/.staging/`. A failing instrument keeps its staging file as evidence and
does not abort the remaining instruments.

- [ ] **Step 1: Write the failing tests**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryUpdateServiceTest {

    @TempDir
    Path directory;

    private Path active;
    private Path archive;
    private RecordingImportService imports;
    private HistoryUpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        active = directory.resolve("active.sqlite");
        archive = directory.resolve("active.sqlite.gz");
        Files.writeString(archive, "placeholder");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
                      snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
                      high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL,
                      low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
                      last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp)
                    """);
            statement.execute("""
                    INSERT INTO historical_price VALUES
                      ('a','US500','MINUTE','2026-08-02T22:52:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-02T22:53:00Z'),
                      ('b','GOLD','MINUTE','2026-08-05T10:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-05T10:01:00Z')
                    """);
        }
        imports = new RecordingImportService();
        service = new HistoryUpdateService(new HistoryCursorReader(active), imports,
                new HistoryDeltaMerger(), directory.resolve(".staging"),
                () -> Instant.parse("2026-08-06T09:14:00Z"));
    }

    @Test
    void updatesEveryStoredInstrumentFromItsOwnCursor() {
        List<HistoryUpdateOutcome> outcomes = service.update(null, null, null, active, archive);

        assertThat(outcomes).extracting(outcome -> outcome.target().epic())
                .containsExactlyInAnyOrder("US500", "GOLD");
        assertThat(imports.requests).extracting(HistoryImportRequest::from)
                .containsExactlyInAnyOrder(
                        Instant.parse("2026-08-02T22:53:00Z"),
                        Instant.parse("2026-08-05T10:01:00Z"));
        assertThat(imports.requests).allSatisfy(request ->
                assertThat(request.to()).isEqualTo(Instant.parse("2026-08-06T09:14:00Z")));
    }

    @Test
    void scopesTheRunToAnExplicitEpic() {
        service.update("US500", null, null, active, archive);

        assertThat(imports.requests).extracting(HistoryImportRequest::epic).containsExactly("US500");
    }

    @Test
    void reportsAlreadyCurrentWithoutCallingTheProvider() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO historical_price VALUES
                      ('c','US500','MINUTE','2026-08-06T09:13:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-06T09:14:00Z')
                    """);
        }

        List<HistoryUpdateOutcome> outcomes = service.update("US500", null, null, active, archive);

        assertThat(outcomes).singleElement()
                .extracting(HistoryUpdateOutcome::status)
                .isEqualTo(HistoryUpdateOutcome.Status.ALREADY_CURRENT);
        assertThat(imports.requests).isEmpty();
    }

    @Test
    void recordsAFailureWithoutAbortingTheOtherInstruments() {
        imports.failFor = "US500";

        List<HistoryUpdateOutcome> outcomes = service.update(null, null, null, active, archive);

        assertThat(outcomes).filteredOn(outcome -> outcome.target().epic().equals("US500"))
                .singleElement()
                .satisfies(outcome -> {
                    assertThat(outcome.status()).isEqualTo(HistoryUpdateOutcome.Status.FAILED);
                    assertThat(outcome.failure()).contains("provider rejected the request");
                });
        assertThat(outcomes).filteredOn(outcome -> outcome.target().epic().equals("GOLD"))
                .singleElement()
                .extracting(HistoryUpdateOutcome::status)
                .isEqualTo(HistoryUpdateOutcome.Status.MERGED);
    }

    @Test
    void failsClosedWhenSeedingAnUnknownInstrumentWithoutAStart() {
        assertThatIllegalStateException()
                .isThrownBy(() -> service.update("NASDAQ", "MINUTE", null, active, archive))
                .withMessageContaining("explicit from");
    }

    /** Stands in for the real Capital-backed stage: writes a minimal valid delta instead of paging. */
    private static final class RecordingImportService extends HistoryImportService {

        private final List<HistoryImportRequest> requests = new java.util.ArrayList<>();
        private String failFor;

        RecordingImportService() {
            super((request, from, to, maxBars) -> {
                throw new UnsupportedOperationException("not used");
            }, new HistoryDatabasePromoter());
        }

        @Override
        public HistoryImportAudit stage(HistoryImportRequest request, Path activeDatabase, Path archive) {
            requests.add(request);
            if (request.epic().equals(failFor)) {
                throw new IllegalStateException("provider rejected the request");
            }
            HistoryStagingStoreFixtures.writeMinimalDelta(request);
            return HistoryStagingStoreFixtures.passingAudit(request);
        }
    }
}
```

- [ ] **Step 2: Add the delta fixture helper**

The orchestration tests need a staged delta without going near the network. Create
`src/test/java/io/g3tech/axetrader/history/HistoryStagingStoreFixtures.java`:

```java
package io.g3tech.axetrader.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HistoryStagingStoreFixtures {

    private HistoryStagingStoreFixtures() {
    }

    static void writeMinimalDelta(HistoryImportRequest request) {
        Path database = request.stagingDatabase();
        try {
            Files.createDirectories(database.getParent());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not create the staging directory", exception);
        }
        String runId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
                      snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
                      high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL,
                      low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
                      last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX historical_price_source_epic_resolution_timestamp
                    ON historical_price (source, epic, resolution, snapshot_time_utc)
                    """);
            statement.execute("""
                    CREATE TABLE history_import_run (
                      import_run_id TEXT PRIMARY KEY, algorithm_version INTEGER NOT NULL, source TEXT NOT NULL,
                      epic TEXT NOT NULL, resolution TEXT NOT NULL, requested_from_utc TEXT NOT NULL,
                      requested_to_utc TEXT NOT NULL, created_at_utc TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE price_exclusion (
                      import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
                      snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
                      PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
            statement.execute("""
                    CREATE TABLE history_import_page (
                      import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
                      payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
                      rejected_count INTEGER NOT NULL,
                      PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
            statement.execute("""
                    CREATE TABLE history_import_closure (
                      import_run_id TEXT NOT NULL, from_utc TEXT NOT NULL, to_utc TEXT NOT NULL,
                      provenance TEXT NOT NULL, payload_hash TEXT NOT NULL,
                      PRIMARY KEY (import_run_id, from_utc, to_utc),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
            statement.execute("INSERT INTO history_import_run VALUES ('" + runId + "', 3, '"
                    + request.source() + "', '" + request.epic() + "', '" + request.resolution() + "', '"
                    + request.from() + "', '" + request.to() + "', '" + request.from() + "')");
            statement.execute("INSERT INTO historical_price VALUES ('" + UUID.randomUUID() + "', '"
                    + request.epic() + "', '" + request.resolution() + "', '" + request.from()
                    + "', 1,1,1,1,1,1,1,1, 10, '" + request.source() + "', '" + request.from() + "')");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not write the delta fixture", exception);
        }
    }

    static HistoryImportAudit passingAudit(HistoryImportRequest request) {
        return new HistoryImportAudit(request.from(), request.to(), request.from(), request.from(),
                1, 1, 0, 1, 0, 0, Map.of(), List.of(), List.of(), true);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=HistoryUpdateServiceTest`

Expected: FAIL — `HistoryUpdateService` and `HistoryUpdateOutcome` do not exist.

`HistoryImportService` is already a non-final class with a non-final `public HistoryImportAudit
stage(HistoryImportRequest, Path, Path)`, and `HistoryDatabasePromoter` already has a public no-arg
constructor, so `RecordingImportService` compiles without changing production code.

Note that the real `HistoryImportService.stage` already calls
`HistoryDatabasePromoter.requirePromotableAudit(audit)` internally, so the audit gate described in the
spec is inherited rather than re-implemented here: a staged delta with duplicates, counts that fail to
reconcile, or an unexplained continuity gap throws before the merger is ever reached. `stage` also
writes a `history_import_completion` row into the staging file; that table is rebuild-specific and is
deliberately not merged into the active database.

- [ ] **Step 4: Implement the outcome record**

```java
package io.g3tech.axetrader.history;

import java.time.Instant;

public record HistoryUpdateOutcome(
        HistoryTarget target,
        Status status,
        Instant fromInclusive,
        Instant toExclusive,
        HistoryImportAudit audit,
        HistoryDeltaMerger.MergeResult merge,
        String failure
) {

    public static HistoryUpdateOutcome alreadyCurrent(HistoryTarget target) {
        return new HistoryUpdateOutcome(target, Status.ALREADY_CURRENT, null, null, null, null, null);
    }

    public static HistoryUpdateOutcome merged(HistoryTarget target, Instant from, Instant to,
                                              HistoryImportAudit audit, HistoryDeltaMerger.MergeResult merge) {
        return new HistoryUpdateOutcome(target, Status.MERGED, from, to, audit, merge, null);
    }

    public static HistoryUpdateOutcome failed(HistoryTarget target, Instant from, Instant to, String failure) {
        return new HistoryUpdateOutcome(target, Status.FAILED, from, to, null, null, failure);
    }

    public enum Status {
        ALREADY_CURRENT,
        MERGED,
        FAILED
    }
}
```

- [ ] **Step 5: Implement the update service**

```java
package io.g3tech.axetrader.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Deliberately not annotated: {@code HistoryUpdateConfiguration} builds it in Task 6. */
public class HistoryUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryUpdateService.class);
    private static final String DEFAULT_SOURCE = "capital";
    private static final String DEFAULT_RESOLUTION = "MINUTE";

    private final HistoryCursorReader cursorReader;
    private final HistoryImportService importService;
    private final HistoryDeltaMerger merger;
    private final Path stagingDirectory;
    private final Supplier<Instant> clock;

    public HistoryUpdateService(HistoryCursorReader cursorReader, HistoryImportService importService,
                                HistoryDeltaMerger merger, Path stagingDirectory, Supplier<Instant> clock) {
        this.cursorReader = Objects.requireNonNull(cursorReader, "cursorReader");
        this.importService = Objects.requireNonNull(importService, "importService");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<HistoryUpdateOutcome> update(String epic, String resolution, Instant configuredFrom,
                                             Path activeDatabase, Path archive) {
        List<HistoryTarget> targets = resolveTargets(epic, resolution);
        Instant now = clock.get();
        List<HistoryUpdateOutcome> outcomes = new ArrayList<>();
        for (HistoryTarget target : targets) {
            outcomes.add(updateOne(target, configuredFrom, now, activeDatabase, archive));
        }
        return List.copyOf(outcomes);
    }

    private List<HistoryTarget> resolveTargets(String epic, String resolution) {
        List<HistoryTarget> stored = cursorReader.storedTargets();
        if (epic == null || epic.isBlank()) {
            return stored;
        }
        List<HistoryTarget> matching = stored.stream()
                .filter(target -> target.epic().equals(epic))
                .filter(target -> resolution == null || resolution.isBlank()
                        || target.resolution().equals(resolution))
                .toList();
        if (!matching.isEmpty()) {
            return matching;
        }
        return List.of(new HistoryTarget(DEFAULT_SOURCE, epic,
                resolution == null || resolution.isBlank() ? DEFAULT_RESOLUTION : resolution));
    }

    private HistoryUpdateOutcome updateOne(HistoryTarget target, Instant configuredFrom, Instant now,
                                           Path activeDatabase, Path archive) {
        Optional<Instant> cursor = cursorReader.lastStoredMinute(target);
        Optional<HistoryUpdateWindow.Window> window =
                HistoryUpdateWindow.resolve(cursor, Optional.ofNullable(configuredFrom), now);
        if (window.isEmpty()) {
            logger.info("{} {} is already current at {}", target.epic(), target.resolution(),
                    cursor.map(Instant::toString).orElse("unknown"));
            return HistoryUpdateOutcome.alreadyCurrent(target);
        }

        Instant from = window.get().fromInclusive();
        Instant to = window.get().toExclusive();
        Path staging = stagingPath(target);
        HistoryImportRequest request =
                new HistoryImportRequest(target.epic(), target.resolution(), from, to, staging, target.source());
        try {
            HistoryImportAudit audit = importService.stage(request, activeDatabase, archive);
            HistoryDeltaMerger.MergeResult merge = merger.merge(staging, activeDatabase);
            deleteQuietly(staging);
            logger.info("{} {} merged [{}, {}): accepted={}, excluded={}, exclusions={}, "
                            + "closures={}, rowsMerged={}, exclusionsMerged={}",
                    target.epic(), target.resolution(), from, to, audit.acceptedMinuteCount(),
                    audit.excludedMinuteCount(), audit.exclusionsByReason(),
                    audit.recognizedSessionClosures().size(), merge.pricesMerged(), merge.exclusionsMerged());
            return HistoryUpdateOutcome.merged(target, from, to, audit, merge);
        } catch (RuntimeException exception) {
            logger.error("{} {} failed over [{}, {}); staging retained at {}",
                    target.epic(), target.resolution(), from, to, staging, exception);
            return HistoryUpdateOutcome.failed(target, from, to, exception.getMessage());
        }
    }

    private Path stagingPath(HistoryTarget target) {
        try {
            Files.createDirectories(stagingDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the staging directory " + stagingDirectory, exception);
        }
        return stagingDirectory.resolve(target.epic() + "-" + target.resolution() + "-"
                + UUID.randomUUID() + ".sqlite");
    }

    private static void deleteQuietly(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException exception) {
            logger.warn("Merged delta but could not remove the staging file {}", staging, exception);
        }
    }
}
```

Note: `resolveTargets` deliberately lets an unknown epic through as a seed target so
`HistoryUpdateWindow.resolve` produces the "explicit from" failure, which is what
`failsClosedWhenSeedingAnUnknownInstrumentWithoutAStart` asserts.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryUpdateServiceTest,HistoryDeltaMergerTest,HistoryCursorReaderTest,HistoryUpdateWindowTest`

Expected: PASS.

- [ ] **Step 7: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/HistoryUpdateOutcome.java \
        src/main/java/io/g3tech/axetrader/history/HistoryUpdateService.java \
        src/main/java/io/g3tech/axetrader/history/HistoryImportService.java \
        src/test/java/io/g3tech/axetrader/history/HistoryUpdateServiceTest.java \
        src/test/java/io/g3tech/axetrader/history/HistoryStagingStoreFixtures.java
git commit -m "feat(history): orchestrate per-instrument delta updates"
git push
```

---

### Task 5: Dirty-data report

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryDirtyDataReport.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryDirtyDataReporter.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryDirtyDataReporterTest.java`

**Interfaces:**

- Consumes: `HistoryTarget`; reads `historical_price` and `price_exclusion` from the active database.
- Produces: `HistoryDirtyDataReporter.report(Path activeDatabase, HistoryTarget target, Instant fromInclusive, Instant toExclusive)` returning `HistoryDirtyDataReport(HistoryTarget target, Instant fromInclusive, Instant toExclusive, long storedMinutes, long excludedMinutes, double dirtyRate, Map<String, Long> exclusionsByReason)`.

`fromInclusive` and `toExclusive` may be null, meaning unbounded on that side. A database with no
`price_exclusion` table reports zero exclusions rather than failing — legacy datasets are ledgerless.

- [ ] **Step 1: Write the failing tests**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HistoryDirtyDataReporterTest {

    @TempDir
    Path directory;

    private Path database;
    private final HistoryDirtyDataReporter reporter = new HistoryDirtyDataReporter();
    private final HistoryTarget target = new HistoryTarget("capital", "US500", "MINUTE");

    @BeforeEach
    void setUp() throws Exception {
        database = directory.resolve("active.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
                      snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
                      high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL,
                      low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
                      last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp)
                    """);
            statement.execute("""
                    INSERT INTO historical_price VALUES
                      ('a','US500','MINUTE','2026-03-01T10:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-03-01T10:00:00Z'),
                      ('b','US500','MINUTE','2026-03-01T10:01:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-03-01T10:00:00Z'),
                      ('c','US500','MINUTE','2026-04-01T10:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-04-01T10:00:00Z'),
                      ('d','GOLD','MINUTE','2026-03-01T10:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-03-01T10:00:00Z')
                    """);
        }
    }

    private void createExclusions() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE price_exclusion (
                      import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
                      snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
                      PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason))
                    """);
            statement.execute("""
                    INSERT INTO price_exclusion VALUES
                      ('r1','capital','US500','MINUTE','2026-03-01T10:02:00Z','CLOSE_BID_ABOVE_ASK','2026-03-01T11:00:00Z'),
                      ('r1','capital','US500','MINUTE','2026-03-01T10:03:00Z','OPEN_BID_ABOVE_ASK','2026-03-01T11:00:00Z'),
                      ('r1','capital','US500','MINUTE','2026-04-01T10:05:00Z','CLOSE_BID_ABOVE_ASK','2026-04-01T11:00:00Z'),
                      ('r1','capital','GOLD','MINUTE','2026-03-01T10:02:00Z','LOW_BID_ABOVE_ASK','2026-03-01T11:00:00Z')
                    """);
        }
    }

    @Test
    void reportsZeroExclusionsForALedgerlessDatabase() {
        HistoryDirtyDataReport report = reporter.report(database, target, null, null);

        assertThat(report.storedMinutes()).isEqualTo(3);
        assertThat(report.excludedMinutes()).isZero();
        assertThat(report.dirtyRate()).isZero();
        assertThat(report.exclusionsByReason()).isEmpty();
    }

    @Test
    void aggregatesExclusionsByReasonForOneInstrument() throws Exception {
        createExclusions();

        HistoryDirtyDataReport report = reporter.report(database, target, null, null);

        assertThat(report.storedMinutes()).isEqualTo(3);
        assertThat(report.excludedMinutes()).isEqualTo(3);
        assertThat(report.exclusionsByReason())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "CLOSE_BID_ABOVE_ASK", 2L, "OPEN_BID_ABOVE_ASK", 1L));
        assertThat(report.dirtyRate()).isCloseTo(0.5, within(0.0001));
    }

    @Test
    void restrictsTheReportToTheRequestedRange() throws Exception {
        createExclusions();

        HistoryDirtyDataReport report = reporter.report(database, target,
                Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"));

        assertThat(report.storedMinutes()).isEqualTo(2);
        assertThat(report.excludedMinutes()).isEqualTo(2);
        assertThat(report.exclusionsByReason())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "CLOSE_BID_ABOVE_ASK", 1L, "OPEN_BID_ABOVE_ASK", 1L));
    }
}
```

`dirtyRate` is excluded minutes over the total minutes the provider offered for the range —
`excludedMinutes / (storedMinutes + excludedMinutes)`. With 3 stored and 3 excluded that is 0.5.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=HistoryDirtyDataReporterTest`

Expected: FAIL — `HistoryDirtyDataReporter` does not exist.

- [ ] **Step 3: Implement the report record**

```java
package io.g3tech.axetrader.history;

import java.time.Instant;
import java.util.Map;

public record HistoryDirtyDataReport(
        HistoryTarget target,
        Instant fromInclusive,
        Instant toExclusive,
        long storedMinutes,
        long excludedMinutes,
        double dirtyRate,
        Map<String, Long> exclusionsByReason
) {

    public HistoryDirtyDataReport {
        exclusionsByReason = Map.copyOf(exclusionsByReason);
    }
}
```

- [ ] **Step 4: Implement the reporter**

```java
package io.g3tech.axetrader.history;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class HistoryDirtyDataReporter {

    public HistoryDirtyDataReport report(Path activeDatabase, HistoryTarget target,
                                         Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(target, "target");
        Path database = Objects.requireNonNull(activeDatabase, "activeDatabase").toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)) {
            throw new IllegalStateException("Active database does not exist: " + database);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database + "?mode=ro")) {
            long stored = countStored(connection, target, fromInclusive, toExclusive);
            Map<String, Long> byReason = hasExclusionLedger(connection)
                    ? countExclusions(connection, target, fromInclusive, toExclusive)
                    : Map.of();
            long excluded = byReason.values().stream().mapToLong(Long::longValue).sum();
            long offered = stored + excluded;
            double dirtyRate = offered == 0 ? 0d : (double) excluded / (double) offered;
            return new HistoryDirtyDataReport(target, fromInclusive, toExclusive,
                    stored, excluded, dirtyRate, byReason);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not build the dirty-data report from " + database, exception);
        }
    }

    private static boolean hasExclusionLedger(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'price_exclusion'")) {
            rows.next();
            return rows.getLong(1) > 0;
        }
    }

    private static long countStored(Connection connection, HistoryTarget target,
                                    Instant fromInclusive, Instant toExclusive) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT snapshot_time_utc) FROM historical_price
                WHERE source = ? AND epic = ? AND resolution = ?
                """);
        List<Object> parameters = targetParameters(target, fromInclusive, toExclusive, sql);
        try (PreparedStatement statement = prepare(connection, sql.toString(), parameters);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Map<String, Long> countExclusions(Connection connection, HistoryTarget target,
                                                     Instant fromInclusive, Instant toExclusive) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT reason, COUNT(DISTINCT snapshot_time_utc) FROM price_exclusion
                WHERE source = ? AND epic = ? AND resolution = ?
                """);
        List<Object> parameters = targetParameters(target, fromInclusive, toExclusive, sql);
        sql.append(" GROUP BY reason ORDER BY 2 DESC, 1");
        Map<String, Long> byReason = new LinkedHashMap<>();
        try (PreparedStatement statement = prepare(connection, sql.toString(), parameters);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                byReason.put(rows.getString(1), rows.getLong(2));
            }
        }
        return byReason;
    }

    private static List<Object> targetParameters(HistoryTarget target, Instant fromInclusive,
                                                 Instant toExclusive, StringBuilder sql) {
        List<Object> parameters = new ArrayList<>(List.of(target.source(), target.epic(), target.resolution()));
        if (fromInclusive != null) {
            sql.append(" AND snapshot_time_utc >= ?");
            parameters.add(fromInclusive.toString());
        }
        if (toExclusive != null) {
            sql.append(" AND snapshot_time_utc < ?");
            parameters.add(toExclusive.toString());
        }
        return parameters;
    }

    private static PreparedStatement prepare(Connection connection, String sql, List<Object> parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
        return statement;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryDirtyDataReporterTest`

Expected: PASS, 3 tests.

- [ ] **Step 6: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/HistoryDirtyDataReport.java \
        src/main/java/io/g3tech/axetrader/history/HistoryDirtyDataReporter.java \
        src/test/java/io/g3tech/axetrader/history/HistoryDirtyDataReporterTest.java
git commit -m "feat(history): report stored dirty-data statistics"
git push
```

---

### Task 6: Wire the UPDATE and REPORT modes

**Files:**

- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryImportProperties.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryImportRunner.java`
- Modify: `src/main/resources/application.yaml:38-48`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryUpdateConfiguration.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryImportPropertiesTest.java`

**Interfaces:**

- Consumes: `HistoryUpdateService.update(...)`, `HistoryDirtyDataReporter.report(...)`, `HistoryCursorReader.storedTargets()`.
- Produces: `HistoryImportProperties.Mode` extended with `UPDATE`, `REPORT`, `ARCHIVE`; a `HistoryCursorReader` and `HistoryUpdateService` bean; a runner that exits non-zero when any instrument failed.

`staging-database` stays required for `PROBE`, `STAGE` and `PROMOTE` only. `UPDATE` derives its own
per-run staging paths under `staging-directory`, defaulting to `data/.staging`.

- [ ] **Step 1: Write the failing property tests**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryImportPropertiesTest {

    private static HistoryImportProperties properties(String mode) {
        return new HistoryImportProperties(true, mode, "US500", "MINUTE",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"),
                null, null, null, null);
    }

    @Test
    void recognisesTheUpdateReportAndArchiveModes() {
        assertThat(properties("update").requiredMode()).isEqualTo(HistoryImportProperties.Mode.UPDATE);
        assertThat(properties("report").requiredMode()).isEqualTo(HistoryImportProperties.Mode.REPORT);
        assertThat(properties("archive").requiredMode()).isEqualTo(HistoryImportProperties.Mode.ARCHIVE);
    }

    @Test
    void defaultsTheStagingDirectory() {
        assertThat(properties("update").stagingDirectory()).isEqualTo(Path.of("data", ".staging"));
    }

    @Test
    void stillRequiresAStagingDatabaseForTheRebuildModes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> properties("stage").requiredStagingDatabase())
                .withMessageContaining("staging-database");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=HistoryImportPropertiesTest`

Expected: FAIL — the record has nine components, not ten, and `UPDATE` is not a known mode.

- [ ] **Step 3: Extend the properties record**

Add the `stagingDirectory` component and the three modes. The full record becomes:

```java
@ConfigurationProperties(prefix = "axe-trader.history-import")
public record HistoryImportProperties(
        boolean enabled,
        String mode,
        String epic,
        String resolution,
        Instant from,
        Instant to,
        Path stagingDatabase,
        Path stagingDirectory,
        Path activeDatabase,
        Path archive
) {

    private static final Path DEFAULT_ACTIVE_DATABASE = Path.of("data", "axe-trader.sqlite");
    private static final Path DEFAULT_ARCHIVE = Path.of("data", "axe-trader.sqlite.gz");
    private static final Path DEFAULT_STAGING_DIRECTORY = Path.of("data", ".staging");

    public HistoryImportProperties {
        activeDatabase = activeDatabase == null ? DEFAULT_ACTIVE_DATABASE : activeDatabase;
        archive = archive == null ? DEFAULT_ARCHIVE : archive;
        stagingDirectory = stagingDirectory == null ? DEFAULT_STAGING_DIRECTORY : stagingDirectory;
    }

    public Mode requiredMode() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalStateException("axe-trader.history-import.mode must be configured");
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown history import mode: " + mode, exception);
        }
    }

    public HistoryImportRequest request() {
        return new HistoryImportRequest(epic, resolution, from, to, requiredStagingDatabase(), "capital");
    }

    public Path requiredStagingDatabase() {
        if (stagingDatabase == null || stagingDatabase.toString().isBlank()) {
            throw new IllegalStateException("axe-trader.history-import.staging-database must be configured");
        }
        return stagingDatabase;
    }

    public enum Mode {
        PROBE,
        STAGE,
        PROMOTE,
        UPDATE,
        REPORT,
        ARCHIVE
    }
}
```

- [ ] **Step 4: Add the update configuration**

`HistoryUpdateService` needs a constructed `HistoryCursorReader` and clock, so declare them as beans
rather than relying on component scanning.

```java
package io.g3tech.axetrader.history;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Configuration
@ConditionalOnProperty(prefix = "axe-trader.history-import", name = "enabled", havingValue = "true")
public class HistoryUpdateConfiguration {

    @Bean
    public HistoryCursorReader historyCursorReader(HistoryImportProperties properties) {
        return new HistoryCursorReader(properties.activeDatabase());
    }

    @Bean
    public HistoryUpdateService historyUpdateService(HistoryCursorReader cursorReader,
                                                     HistoryImportService importService,
                                                     HistoryDeltaMerger merger,
                                                     HistoryImportProperties properties) {
        return new HistoryUpdateService(cursorReader, importService, merger,
                properties.stagingDirectory(), Instant::now);
    }
}
```

`HistoryUpdateService` carries no stereotype annotation, so this configuration is its only source and
there is no duplicate-bean conflict.

- [ ] **Step 5: Extend the runner**

Replace the `run` method of `HistoryImportRunner` and add the new dependencies and helpers:

```java
    private final HistoryImportProperties properties;
    private final HistoryImportService service;
    private final HistoryUpdateService updateService;
    private final HistoryDirtyDataReporter reporter;
    private final HistoryCursorReader cursorReader;
    private int exitCode;

    public HistoryImportRunner(HistoryImportProperties properties, HistoryImportService service,
                               HistoryUpdateService updateService, HistoryDirtyDataReporter reporter,
                               HistoryCursorReader cursorReader) {
        this.properties = properties;
        this.service = service;
        this.updateService = updateService;
        this.reporter = reporter;
        this.cursorReader = cursorReader;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        switch (properties.requiredMode()) {
            case PROBE -> logAudit("Probe", service.probe(properties.request(),
                    properties.activeDatabase(), properties.archive()));
            case STAGE -> logAudit("Staged import", service.stage(properties.request(),
                    properties.activeDatabase(), properties.archive()));
            case PROMOTE -> {
                Path backup = service.promote(properties.requiredStagingDatabase(),
                        properties.activeDatabase(), properties.archive());
                logger.info("Promoted staged history; legacy database backup is {}", backup);
            }
            case UPDATE -> runUpdate();
            case REPORT -> runReport();
            case ARCHIVE -> throw new IllegalStateException("Archive mode is implemented in Task 7");
        }
    }

    private void runUpdate() {
        List<HistoryUpdateOutcome> outcomes = updateService.update(properties.epic(), properties.resolution(),
                properties.from(), properties.activeDatabase(), properties.archive());
        long merged = outcomes.stream()
                .filter(outcome -> outcome.status() == HistoryUpdateOutcome.Status.MERGED).count();
        long failed = outcomes.stream()
                .filter(outcome -> outcome.status() == HistoryUpdateOutcome.Status.FAILED).count();
        long current = outcomes.stream()
                .filter(outcome -> outcome.status() == HistoryUpdateOutcome.Status.ALREADY_CURRENT).count();
        logger.info("Update complete: {} instrument(s) merged, {} already current, {} failed",
                merged, current, failed);
        if (failed > 0) {
            exitCode = 1;
        }
    }

    private void runReport() {
        List<HistoryTarget> targets = properties.epic() == null || properties.epic().isBlank()
                ? cursorReader.storedTargets()
                : cursorReader.storedTargets().stream()
                        .filter(target -> target.epic().equals(properties.epic()))
                        .toList();
        for (HistoryTarget target : targets) {
            HistoryDirtyDataReport report = reporter.report(properties.activeDatabase(), target,
                    properties.from(), properties.to());
            logger.info("Dirty-data report {} {} [{}, {}): stored={}, excluded={}, dirtyRate={}, byReason={}",
                    target.epic(), target.resolution(), report.fromInclusive(), report.toExclusive(),
                    report.storedMinutes(), report.excludedMinutes(),
                    String.format("%.4f%%", report.dirtyRate() * 100), report.exclusionsByReason());
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
```

Change the class declaration to `implements ApplicationRunner, ExitCodeGenerator` and add the imports
`org.springframework.boot.ExitCodeGenerator`, `io.g3tech.axetrader.history.HistoryTarget` (same
package, so no import needed) and `java.util.List`.

- [ ] **Step 6: Add the configuration keys**

In `src/main/resources/application.yaml`, add one line inside the existing `history-import` block,
directly after `staging-database`:

```yaml
    staging-directory: ${HISTORY_IMPORT_STAGING_DIRECTORY:data/.staging}
```

Add `data/.staging/` to `.gitignore`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryImportPropertiesTest,HistoryImportServiceTest,HistoryUpdateServiceTest`

Expected: PASS. One existing call site constructs the record directly —
`HistoryImportServiceTest.java:621` — and needs the new component inserted after `stagingDatabase`:

```java
        HistoryImportProperties properties = new HistoryImportProperties(
                true, "promote", null, null, null, null, request.stagingDatabase(), null, active, archive);
```

- [ ] **Step 8: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/ src/main/resources/application.yaml .gitignore \
        src/test/java/io/g3tech/axetrader/history/HistoryImportPropertiesTest.java
git commit -m "feat(history): add update and report modes"
git push
```

---

### Task 7: Archive mode

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryArchiveWriter.java`
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryImportRunner.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryArchiveWriterTest.java`

**Interfaces:**

- Consumes: the active database path and archive path from `HistoryImportProperties`.
- Produces: `HistoryArchiveWriter.rewrite(Path activeDatabase, Path archive)` returning the archive `Path`.

Writes to a temporary sibling and moves it into place, so an interrupted run never leaves a truncated
archive that `DatabaseBootstrap` would later restore from.

- [ ] **Step 1: Write the failing test**

```java
package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryArchiveWriterTest {

    @TempDir
    Path directory;

    private final HistoryArchiveWriter writer = new HistoryArchiveWriter();

    @Test
    void rewritesTheArchiveFromTheActiveDatabase() throws Exception {
        Path active = directory.resolve("active.sqlite");
        Path archive = directory.resolve("active.sqlite.gz");
        Files.writeString(active, "database-contents");
        Files.writeString(archive, "stale");

        writer.rewrite(active, archive);

        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            input.transferTo(restored);
            assertThat(restored.toString()).isEqualTo("database-contents");
        }
        assertThat(Files.list(directory)).hasSize(2);
    }

    @Test
    void failsClosedWhenTheActiveDatabaseIsMissing() {
        assertThatIllegalStateException().isThrownBy(() ->
                writer.rewrite(directory.resolve("absent.sqlite"), directory.resolve("absent.sqlite.gz")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=HistoryArchiveWriterTest`

Expected: FAIL — `HistoryArchiveWriter` does not exist.

- [ ] **Step 3: Implement the archive writer**

```java
package io.g3tech.axetrader.history;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

@Component
public class HistoryArchiveWriter {

    public Path rewrite(Path activeDatabase, Path archive) {
        Path active = Objects.requireNonNull(activeDatabase, "activeDatabase").toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(active)) {
            throw new IllegalStateException("Active database does not exist: " + active);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".new-", ".tmp");
            try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                Files.copy(active, output);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("Could not rewrite the archive " + target, exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing further to do; the temporary file is not referenced anywhere.
        }
    }
}
```

- [ ] **Step 4: Wire the ARCHIVE case**

In `HistoryImportRunner`, inject `HistoryArchiveWriter archiveWriter` as a further constructor
parameter and replace the placeholder case:

```java
            case ARCHIVE -> logger.info("Rewrote archive {}",
                    archiveWriter.rewrite(properties.activeDatabase(), properties.archive()));
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=HistoryArchiveWriterTest`

Expected: PASS, 2 tests.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`

Expected: PASS. Every pre-existing test, including `BacktestRunnerIntrabarTest`, must still pass.

- [ ] **Step 7: Commit and push**

```bash
git add src/main/java/io/g3tech/axetrader/history/HistoryArchiveWriter.java \
        src/main/java/io/g3tech/axetrader/history/HistoryImportRunner.java \
        src/test/java/io/g3tech/axetrader/history/HistoryArchiveWriterTest.java
git commit -m "feat(history): rewrite the archive on demand"
git push
```

---

### Task 8: Runbook and operational verification

**Files:**

- Modify: `docs/local-price-history.md`
- Modify: `TODO.md`
- Modify: `CLAUDE.md`

**Interfaces:**

- Consumes: the `update`, `report` and `archive` modes from Tasks 6 and 7.
- Produces: the documented operational procedure and a recorded first real run.

- [ ] **Step 1: Document the three new commands**

Add an "Update" section to `docs/local-price-history.md` above the existing "Probe" section, since it
is now the routine operation and probe/stage/promote are the rebuild path:

```bash
# Top up every stored instrument from its own cursor to the last completed minute.
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update"

# Top up one instrument.
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update --axe-trader.history-import.epic=US500"

# Seed a new instrument (requires an explicit resolution and start).
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--spring.config.import=file:/Users/gertehlers/Development/projects/axe-trader/.env[.properties] --axe-trader.history-import.enabled=true --axe-trader.history-import.mode=update --axe-trader.history-import.epic=GOLD --axe-trader.history-import.resolution=MINUTE --axe-trader.history-import.from=2024-01-01T00:00:00Z"

# Dirty-data report (no network).
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=report --axe-trader.history-import.epic=US500"

# Rebuild the committed gzip snapshot after top-ups.
./mvnw spring-boot:run -Dspring-boot.run.main-class=io.g3tech.axetrader.AxeTraderApplication -Dspring-boot.run.arguments="--axe-trader.history-import.enabled=true --axe-trader.history-import.mode=archive"
```

State explicitly that `update` never rewrites `data/axe-trader.sqlite.gz`, so `archive` must be run
before committing the snapshot, and that a failed instrument leaves its staging file under
`data/.staging/` as evidence.

- [ ] **Step 2: Run the real top-up**

Record the reported window, accepted and excluded counts, exclusions by reason, recognised closures,
rows merged and the resulting cursor. Then verify directly:

```bash
sqlite3 data/axe-trader.sqlite "SELECT epic, resolution, COUNT(*), MIN(snapshot_time_utc), MAX(snapshot_time_utc) FROM historical_price GROUP BY 1,2;"
sqlite3 data/axe-trader.sqlite "SELECT reason, COUNT(*) FROM price_exclusion GROUP BY 1 ORDER BY 2 DESC;"
```

- [ ] **Step 3: Verify idempotency against the real database**

Run `update` a second time immediately. Expected: every instrument reports `ALREADY_CURRENT`, no
network paging occurs, and the row count from Step 2 is unchanged.

- [ ] **Step 4: Run a backtest on the extended dataset**

Run: `./mvnw spring-boot:run`

Expected: the backtest completes and reports a bar count consistent with the extended range.

- [ ] **Step 5: Record the outcome and commit**

Add a dated entry to `docs/local-price-history.md` recording the first `update` run's audit numbers,
mirroring the existing "2026-08-03 paced v2 completion" section. Update the `TODO.md` price-history
section to state that incremental top-up is available and how to run it. Add a one-line pointer to
`CLAUDE.md`'s Database section that history is topped up with `history-import.mode=update`.

```bash
git add docs/local-price-history.md TODO.md CLAUDE.md
git commit -m "docs(history): record incremental price top-up workflow"
git push
```
