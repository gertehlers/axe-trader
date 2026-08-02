package io.g3tech.axetrader.history;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HistoryStagingStore implements AutoCloseable {

    private static final String INSERT_PAGE = """
            INSERT OR IGNORE INTO history_import_page (
                import_run_id, requested_from_utc, requested_to_utc, payload_hash,
                received_count, accepted_count, rejected_count)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_PRICE = """
            INSERT OR IGNORE INTO historical_price (
                id, epic, resolution, snapshot_time_utc,
                open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask,
                last_traded_volume, source, ingestion_time_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_EXCLUSION = """
            INSERT OR IGNORE INTO price_exclusion (
                import_run_id, source, epic, resolution, snapshot_time_utc, reason, detected_at_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final Connection connection;
    private final PriceValidator validator;

    private HistoryStagingStore(Connection connection, PriceValidator validator) {
        this.connection = connection;
        this.validator = validator;
    }

    public static HistoryStagingStore open(Path database) {
        try {
            if (database.getParent() != null) {
                Files.createDirectories(database.getParent());
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            applySchema(connection);
            return new HistoryStagingStore(connection, new PriceValidator());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not open history staging store at " + database, exception);
        }
    }

    public void writePage(ImportedPage page, HistoryImportRequest request) {
        validatePageBounds(page, request);
        List<ValidatedPrice> prices = validate(page.prices(), request);
        long acceptedCount = prices.stream().filter(ValidatedPrice::accepted).count();
        long rejectedCount = prices.size() - acceptedCount;
        String importRunId = importRunId(request);
        Instant detectedAt = Instant.now();
        try {
            connection.setAutoCommit(false);
            if (!insertPage(page, importRunId, acceptedCount, rejectedCount)) {
                connection.commit();
                return;
            }
            for (ValidatedPrice price : prices) {
                if (price.accepted()) {
                    insertPrice(price.price(), request, detectedAt);
                } else {
                    insertExclusions(price, request, importRunId, detectedAt);
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            rollback();
            throw new IllegalStateException("Could not write imported history page", exception);
        } finally {
            restoreAutoCommit();
        }
    }

    public long countAccepted(HistoryImportRequest request) {
        String sql = """
                SELECT COUNT(*) FROM historical_price
                WHERE source = ? AND epic = ? AND resolution = ?
                    AND snapshot_time_utc >= ? AND snapshot_time_utc < ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setRequestIdentity(statement, request);
            return singleLong(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count accepted prices", exception);
        }
    }

    public List<PriceExclusion> exclusions(HistoryImportRequest request) {
        String sql = """
                SELECT snapshot_time_utc, reason FROM price_exclusion
                WHERE import_run_id = ?
                ORDER BY snapshot_time_utc, reason
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                List<PriceExclusion> exclusions = new ArrayList<>();
                while (rows.next()) {
                    exclusions.add(new PriceExclusion(Instant.parse(rows.getString("snapshot_time_utc")),
                            rows.getString("reason")));
                }
                return List.copyOf(exclusions);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read price exclusions", exception);
        }
    }

    public HistoryImportAudit audit(HistoryImportRequest request) {
        PageCounts pageCounts = pageCounts(request);
        long acceptedMinutes = countAccepted(request);
        long excludedMinutes = excludedMinuteCount(request);
        Map<String, Long> exclusionsByReason = exclusionsByReason(request);
        Instant[] actualBounds = actualBounds(request);
        long malformedAcceptedCount = malformedAcceptedCount(request);
        long rejectedWithoutExclusionCount = Math.max(0, pageCounts.rejectedCount() - excludedMinutes);
        return new HistoryImportAudit(
                request.from(), request.to(), actualBounds[0], actualBounds[1],
                pageCounts.receivedCount(), pageCounts.acceptedCount(), pageCounts.rejectedCount(),
                acceptedMinutes, excludedMinutes, Math.max(0, pageCounts.acceptedCount() - acceptedMinutes),
                exclusionsByReason, malformedAcceptedCount == 0 && rejectedWithoutExclusionCount == 0);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not close history staging store", exception);
        }
    }

    public record PriceExclusion(Instant snapshotTime, String reason) { }

    private List<ValidatedPrice> validate(List<ImportedPrice> prices, HistoryImportRequest request) {
        List<ValidatedPrice> validated = new ArrayList<>();
        for (ImportedPrice price : prices) {
            Set<PriceValidationFailure> failures = validator.validate(price);
            if (price != null && price.timestamp() != null
                    && (price.timestamp().isBefore(request.from()) || !price.timestamp().isBefore(request.to()))) {
                failures.add(PriceValidationFailure.TIMESTAMP_OUT_OF_RANGE);
            }
            if (price == null || price.timestamp() == null) {
                throw new IllegalArgumentException("Imported prices must have a timestamp to record exclusions");
            }
            validated.add(new ValidatedPrice(price, Set.copyOf(failures)));
        }
        return validated;
    }

    private boolean insertPage(ImportedPage page, String importRunId, long acceptedCount, long rejectedCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PAGE)) {
            statement.setString(1, importRunId);
            statement.setString(2, page.requestedFrom().toString());
            statement.setString(3, page.requestedTo().toString());
            statement.setString(4, page.payloadHash());
            statement.setLong(5, page.prices().size());
            statement.setLong(6, acceptedCount);
            statement.setLong(7, rejectedCount);
            return statement.executeUpdate() == 1;
        }
    }

    private void insertPrice(ImportedPrice price, HistoryImportRequest request, Instant ingestedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PRICE)) {
            int index = 1;
            statement.setString(index++, UUID.randomUUID().toString());
            statement.setString(index++, request.epic());
            statement.setString(index++, request.resolution());
            statement.setString(index++, price.timestamp().toString());
            statement.setBigDecimal(index++, price.openBid());
            statement.setBigDecimal(index++, price.openAsk());
            statement.setBigDecimal(index++, price.highBid());
            statement.setBigDecimal(index++, price.highAsk());
            statement.setBigDecimal(index++, price.lowBid());
            statement.setBigDecimal(index++, price.lowAsk());
            statement.setBigDecimal(index++, price.closeBid());
            statement.setBigDecimal(index++, price.closeAsk());
            statement.setLong(index++, price.lastTradedVolume());
            statement.setString(index++, request.source());
            statement.setString(index, ingestedAt.toString());
            statement.executeUpdate();
        }
    }

    private void insertExclusions(ValidatedPrice invalidPrice, HistoryImportRequest request, String importRunId,
                                  Instant detectedAt) throws SQLException {
        for (PriceValidationFailure failure : invalidPrice.failures()) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_EXCLUSION)) {
                statement.setString(1, importRunId);
                statement.setString(2, request.source());
                statement.setString(3, request.epic());
                statement.setString(4, request.resolution());
                statement.setString(5, invalidPrice.price().timestamp().toString());
                statement.setString(6, failure.name());
                statement.setString(7, detectedAt.toString());
                statement.executeUpdate();
            }
        }
    }

    private static void validatePageBounds(ImportedPage page, HistoryImportRequest request) {
        if (page.requestedFrom().isBefore(request.from()) || page.requestedTo().isAfter(request.to())) {
            throw new IllegalArgumentException("Page bounds must be within the import range");
        }
    }

    private PageCounts pageCounts(HistoryImportRequest request) {
        String sql = """
                SELECT COALESCE(SUM(received_count), 0), COALESCE(SUM(accepted_count), 0),
                    COALESCE(SUM(rejected_count), 0)
                FROM history_import_page
                WHERE import_run_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Page count query returned no row");
                }
                return new PageCounts(rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit imported page counts", exception);
        }
    }

    private long excludedMinuteCount(HistoryImportRequest request) {
        String sql = """
                SELECT COUNT(DISTINCT snapshot_time_utc) FROM price_exclusion
                WHERE import_run_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            return singleLong(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count excluded prices", exception);
        }
    }

    private Map<String, Long> exclusionsByReason(HistoryImportRequest request) {
        String sql = """
                SELECT reason, COUNT(*) FROM price_exclusion
                WHERE import_run_id = ?
                GROUP BY reason ORDER BY reason
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                Map<String, Long> counts = new LinkedHashMap<>();
                while (rows.next()) {
                    counts.put(rows.getString(1), rows.getLong(2));
                }
                return counts;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not group price exclusions", exception);
        }
    }

    private Instant[] actualBounds(HistoryImportRequest request) {
        String sql = """
                SELECT MIN(snapshot_time_utc), MAX(snapshot_time_utc) FROM historical_price
                WHERE source = ? AND epic = ? AND resolution = ?
                    AND snapshot_time_utc >= ? AND snapshot_time_utc < ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setRequestIdentity(statement, request);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Actual bounds query returned no row");
                }
                String first = rows.getString(1);
                String last = rows.getString(2);
                return new Instant[] {first == null ? null : Instant.parse(first), last == null ? null : Instant.parse(last)};
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit actual price bounds", exception);
        }
    }

    private long malformedAcceptedCount(HistoryImportRequest request) {
        String sql = """
                SELECT COUNT(*) FROM historical_price
                WHERE source = ? AND epic = ? AND resolution = ?
                    AND snapshot_time_utc >= ? AND snapshot_time_utc < ?
                    AND (open_bid <= 0 OR open_ask <= 0 OR high_bid <= 0 OR high_ask <= 0
                        OR low_bid <= 0 OR low_ask <= 0 OR close_bid <= 0 OR close_ask <= 0
                        OR open_bid > open_ask OR high_bid > high_ask OR low_bid > low_ask
                        OR close_bid > close_ask)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setRequestIdentity(statement, request);
            return singleLong(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit accepted price validity", exception);
        }
    }

    private static String importRunId(HistoryImportRequest request) {
        String identity = String.join("\n", request.source(), request.epic(), request.resolution(),
                request.from().toString(), request.to().toString());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void applySchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL,
                      epic varchar(255), resolution varchar(255), snapshot_time_utc timestamp,
                      open_bid float NOT NULL, open_ask float NOT NULL, high_bid float NOT NULL, high_ask float NOT NULL,
                      low_bid float NOT NULL, low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
                      last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
                    ON historical_price (source, epic, resolution, snapshot_time_utc)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS price_exclusion (
                      import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
                      snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
                      PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS history_import_page (
                      import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
                      payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
                      rejected_count INTEGER NOT NULL,
                      PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc)
                    )
                    """);
        }
    }

    private static void setRequestIdentity(PreparedStatement statement, HistoryImportRequest request) throws SQLException {
        statement.setString(1, request.source());
        statement.setString(2, request.epic());
        statement.setString(3, request.resolution());
        statement.setString(4, request.from().toString());
        statement.setString(5, request.to().toString());
    }

    private static long singleLong(PreparedStatement statement) throws SQLException {
        try (var rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Count query returned no row");
            }
            return rows.getLong(1);
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not restore SQLite auto-commit", exception);
        }
    }

    private record ValidatedPrice(ImportedPrice price, Set<PriceValidationFailure> failures) {
        private boolean accepted() {
            return failures.isEmpty();
        }
    }

    private record PageCounts(long receivedCount, long acceptedCount, long rejectedCount) { }
}
