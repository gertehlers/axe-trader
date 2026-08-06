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
