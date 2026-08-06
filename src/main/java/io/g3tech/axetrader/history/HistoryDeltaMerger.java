package io.g3tech.axetrader.history;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Merges an audited staged delta into the active price database.
 *
 * <p>The active database is only opened for writing once its delta has passed the staged audit, and
 * every table is merged inside a single transaction so a failure leaves the active database
 * unchanged.
 */
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
                MergeResult result = mergeTables(connection);
                connection.commit();
                return result;
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

    /** Runs before {@code history_import_page}, {@code history_import_closure} and {@code price_exclusion}. */
    private static MergeResult mergeTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT OR IGNORE INTO history_import_run
                    SELECT import_run_id, algorithm_version, source, epic, resolution,
                           requested_from_utc, requested_to_utc, created_at_utc
                    FROM delta.history_import_run
                    """);
            long prices = statement.executeUpdate(
                    "INSERT OR IGNORE INTO historical_price (" + PRICE_COLUMNS + ") SELECT "
                            + PRICE_COLUMNS + " FROM delta.historical_price");
            long exclusions = statement.executeUpdate("""
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
            return new MergeResult(prices, exclusions);
        }
    }

    /**
     * A legacy active database has {@code historical_price} from Flyway V1 but neither the ledger
     * tables nor the unique index. Without that index {@code INSERT OR IGNORE} inserts duplicates
     * instead of ignoring them, so creating it is mandatory — and failing to create it because the
     * table already holds duplicates is the correct fail-closed signal.
     */
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
