package io.g3tech.axetrader.history;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** SQLite-backed, idempotent staging store for an independently auditable history import. */
public final class HistoryStagingStore {

    private static final String PRICE_FIELDS = """
            open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask
            """;

    private final Path stagingDatabase;
    private final HistoryImportRequest boundRequest;

    public HistoryStagingStore(HistoryImportRequest request) {
        this(Objects.requireNonNull(request, "request").stagingDatabase(), request);
    }

    public HistoryStagingStore(Path stagingDatabase) {
        this(stagingDatabase, null);
    }

    private HistoryStagingStore(Path stagingDatabase, HistoryImportRequest boundRequest) {
        this.stagingDatabase = Objects.requireNonNull(stagingDatabase, "stagingDatabase");
        this.boundRequest = boundRequest;
        initialize();
    }

    /** Writes one fetched page and its provenance in a single SQLite transaction. */
    public void write(ImportedPage page) {
        if (boundRequest == null) {
            throw new IllegalStateException("a history import request is required to write a staged page");
        }
        write(boundRequest, page);
    }

    public void write(HistoryImportRequest request, ImportedPage page) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(page, "page");
        if (boundRequest == null || !boundRequest.equals(request)) {
            throw new IllegalStateException("staging writes require the store's original history import request");
        }
        validatePageWindow(request, page);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                writePageMetadata(connection, request, page);
                for (HistoricalPrice price : page.prices()) {
                    writePrice(connection, request, price);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not stage historical price page", exception);
        }
    }

    /** Computes every promotion gate from the staging database, never from the active database. */
    public HistoryImportAudit audit(HistoryImportRequest request) {
        Objects.requireNonNull(request, "request");
        try (Connection connection = connection()) {
            long rowCount = scalar(connection, "SELECT COUNT(*) FROM historical_price");
            long distinctTimestampCount = scalar(connection, "SELECT COUNT(DISTINCT snapshot_time_utc) FROM historical_price");
            PriceAuditCounts priceCounts = priceAuditCounts(connection);
            long coverageGapCount = coverageGapCount(connection, request);
            long outOfWindowRowCount = outOfWindowRowCount(connection, request);
            return new HistoryImportAudit(
                    rowCount,
                    distinctTimestampCount,
                    duplicateCount(connection),
                    priceCounts.invalidFieldCount(),
                    coverageGapCount,
                    priceCounts.crossedOpenCount(),
                    priceCounts.crossedHighCount(),
                    priceCounts.crossedLowCount(),
                    priceCounts.crossedCloseCount(),
                    outOfWindowRowCount);
        } catch (SQLException exception) {
            throw new IllegalStateException("could not audit staged historical prices", exception);
        }
    }

    private void initialize() {
        Path parent = stagingDatabase.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS historical_price (
                            id TEXT PRIMARY KEY NOT NULL,
                            epic TEXT NOT NULL,
                            resolution TEXT NOT NULL,
                            snapshot_time_utc TEXT NOT NULL,
                            open_bid REAL,
                            open_ask REAL,
                            high_bid REAL,
                            high_ask REAL,
                            low_bid REAL,
                            low_ask REAL,
                            close_bid REAL,
                            close_ask REAL,
                            last_traded_volume INTEGER NOT NULL,
                            source TEXT NOT NULL,
                            ingestion_time_utc TEXT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
                        ON historical_price (source, epic, resolution, snapshot_time_utc)
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS history_import_page (
                            id TEXT PRIMARY KEY NOT NULL,
                            source TEXT NOT NULL,
                            epic TEXT NOT NULL,
                            resolution TEXT NOT NULL,
                            requested_from_utc TEXT NOT NULL,
                            requested_to_utc TEXT NOT NULL,
                            payload_hash TEXT NOT NULL,
                            recorded_at_utc TEXT NOT NULL,
                            UNIQUE (source, epic, resolution, requested_from_utc, requested_to_utc, payload_hash)
                        )
                        """);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not create staging database directory", exception);
        } catch (SQLException exception) {
            throw new IllegalStateException("could not initialize history staging database", exception);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + stagingDatabase.toAbsolutePath());
    }

    private static void writePageMetadata(Connection connection, HistoryImportRequest request, ImportedPage page)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO history_import_page
                    (id, source, epic, resolution, requested_from_utc, requested_to_utc, payload_hash, recorded_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, request.source());
            statement.setString(3, request.epic());
            statement.setString(4, request.resolution());
            statement.setString(5, page.requestedFrom().toString());
            statement.setString(6, page.requestedTo().toString());
            statement.setString(7, page.payloadHash());
            statement.setString(8, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static void writePrice(Connection connection, HistoryImportRequest request, HistoricalPrice price)
            throws SQLException {
        Objects.requireNonNull(price, "page price");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO historical_price
                    (id, epic, resolution, snapshot_time_utc, open_bid, open_ask, high_bid, high_ask,
                     low_bid, low_ask, close_bid, close_ask, last_traded_volume, source, ingestion_time_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, request.epic());
            statement.setString(3, request.resolution());
            statement.setString(4, Objects.requireNonNull(price.getSnapshotTimeUtc(), "snapshotTimeUtc").toString());
            setFiniteDouble(statement, 5, price.getOpenBid());
            setFiniteDouble(statement, 6, price.getOpenAsk());
            setFiniteDouble(statement, 7, price.getHighBid());
            setFiniteDouble(statement, 8, price.getHighAsk());
            setFiniteDouble(statement, 9, price.getLowBid());
            setFiniteDouble(statement, 10, price.getLowAsk());
            setFiniteDouble(statement, 11, price.getCloseBid());
            setFiniteDouble(statement, 12, price.getCloseAsk());
            statement.setInt(13, price.getLastTradedVolume());
            statement.setString(14, request.source());
            statement.setString(15, price.getIngestionTimeUtc() == null
                    ? Instant.now().toString() : price.getIngestionTimeUtc().toString());
            statement.executeUpdate();
        }
    }

    private static void setFiniteDouble(PreparedStatement statement, int index, double value) throws SQLException {
        if (Double.isFinite(value)) {
            statement.setDouble(index, value);
        } else {
            statement.setString(index, Double.toString(value));
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static PriceAuditCounts priceAuditCounts(Connection connection) throws SQLException {
        String invalidExpression = invalidExpression(PRICE_FIELDS);
        String sql = """
                SELECT %s AS invalid_fields,
                       SUM(CASE WHEN open_bid > open_ask THEN 1 ELSE 0 END) AS crossed_open,
                       SUM(CASE WHEN high_bid > high_ask THEN 1 ELSE 0 END) AS crossed_high,
                       SUM(CASE WHEN low_bid > low_ask THEN 1 ELSE 0 END) AS crossed_low,
                       SUM(CASE WHEN close_bid > close_ask THEN 1 ELSE 0 END) AS crossed_close
                FROM historical_price
                """.formatted(invalidExpression);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new PriceAuditCounts(result.getLong("invalid_fields"), result.getLong("crossed_open"),
                        result.getLong("crossed_high"), result.getLong("crossed_low"), result.getLong("crossed_close"));
            }
        }
    }

    private static long duplicateCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(rows_with_key - 1), 0)
                FROM (
                    SELECT COUNT(*) AS rows_with_key
                    FROM historical_price
                    GROUP BY source, epic, resolution, snapshot_time_utc
                    HAVING COUNT(*) > 1
                )
                """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long outOfWindowRowCount(Connection connection, HistoryImportRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM historical_price
                WHERE source != ? OR epic != ? OR resolution != ?
                   OR snapshot_time_utc < ? OR snapshot_time_utc >= ?
                """)) {
            statement.setString(1, request.source());
            statement.setString(2, request.epic());
            statement.setString(3, request.resolution());
            statement.setString(4, request.from().toString());
            statement.setString(5, request.to().toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String invalidExpression(String fields) {
        String[] fieldNames = fields.replace("\n", " ").trim().split("\\s*,\\s*");
        StringBuilder expression = new StringBuilder("COALESCE(SUM(");
        for (int index = 0; index < fieldNames.length; index++) {
            if (index > 0) {
                expression.append(" + ");
            }
            String field = fieldNames[index];
            expression.append("CASE WHEN ")
                    .append(field).append(" IS NULL OR typeof(").append(field).append(") NOT IN ('real', 'integer')")
                    .append(" OR ").append(field).append(" <= 0 OR abs(").append(field)
                    .append(") > 1.7976931348623157e308 THEN 1 ELSE 0 END");
        }
        return expression.append("), 0)").toString();
    }

    private static long coverageGapCount(Connection connection, HistoryImportRequest request) throws SQLException {
        long intervalSeconds = resolutionDuration(request.resolution()).toSeconds();
        String sql = """
                WITH RECURSIVE expected(timestamp_epoch) AS (
                    SELECT unixepoch(?)
                    UNION ALL
                    SELECT timestamp_epoch + ? FROM expected
                    WHERE timestamp_epoch + ? < unixepoch(?)
                )
                SELECT COUNT(*) FROM expected
                WHERE strftime('%w', timestamp_epoch, 'unixepoch') NOT IN ('0', '6')
                  AND NOT EXISTS (
                    SELECT 1 FROM historical_price
                    WHERE source = ? AND epic = ? AND resolution = ?
                      AND unixepoch(snapshot_time_utc) = timestamp_epoch
                  )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.from().toString());
            statement.setLong(2, intervalSeconds);
            statement.setLong(3, intervalSeconds);
            statement.setString(4, request.to().toString());
            statement.setString(5, request.source());
            statement.setString(6, request.epic());
            statement.setString(7, request.resolution());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static Duration resolutionDuration(String resolution) {
        return CapitalHistoryResolution.requireSupported(resolution).duration();
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void validatePageWindow(HistoryImportRequest request, ImportedPage page) {
        if (!page.requestedFrom().isBefore(page.requestedTo())
                || page.requestedFrom().isBefore(request.from())
                || page.requestedTo().isAfter(request.to())) {
            throw new IllegalArgumentException("staged page must be contained by the history import window");
        }
        for (HistoricalPrice price : page.prices()) {
            Instant snapshotTime = Objects.requireNonNull(price, "page price").getSnapshotTimeUtc();
            if (snapshotTime == null || snapshotTime.isBefore(request.from()) || !snapshotTime.isBefore(request.to())) {
                throw new IllegalArgumentException("staged price must be contained by the history import window");
            }
        }
    }

    private record PriceAuditCounts(long invalidFieldCount, long crossedOpenCount, long crossedHighCount,
                                    long crossedLowCount, long crossedCloseCount) {
    }
}
