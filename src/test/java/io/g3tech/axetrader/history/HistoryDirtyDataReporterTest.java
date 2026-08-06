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
    void countsAMinuteOnceEvenWhenItViolatesSeveralFields() throws Exception {
        createExclusions();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO price_exclusion VALUES
                      ('r1','capital','US500','MINUTE','2026-03-01T10:02:00Z','HIGH_BID_ABOVE_ASK','2026-03-01T11:00:00Z')
                    """);
        }

        HistoryDirtyDataReport report = reporter.report(database, target, null, null);

        assertThat(report.exclusionsByReason()).containsEntry("HIGH_BID_ABOVE_ASK", 1L);
        assertThat(report.excludedMinutes()).isEqualTo(3);
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
