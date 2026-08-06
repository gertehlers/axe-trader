package io.g3tech.axetrader.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds a minimal staged delta so orchestration tests never touch the network. */
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
