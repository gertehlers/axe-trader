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
