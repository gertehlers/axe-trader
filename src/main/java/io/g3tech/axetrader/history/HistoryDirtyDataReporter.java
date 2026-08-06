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

/**
 * Summarises stored versus excluded minutes for an instrument. Reads only; never contacts a provider.
 */
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
            boolean ledger = hasExclusionLedger(connection);
            Map<String, Long> byReason = ledger
                    ? countExclusions(connection, target, fromInclusive, toExclusive)
                    : Map.of();
            // Summing byReason would double-count a minute that violates several fields, which is
            // common in this dataset: 27 excluded minutes produced 34 reason rows on 2026-08-06.
            long excluded = ledger ? countExcludedMinutes(connection, target, fromInclusive, toExclusive) : 0L;
            long offered = stored + excluded;
            double dirtyRate = offered == 0 ? 0d : (double) excluded / (double) offered;
            return new HistoryDirtyDataReport(target, fromInclusive, toExclusive,
                    stored, excluded, dirtyRate, byReason);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not build the dirty-data report from " + database, exception);
        }
    }

    /** Legacy datasets predate the ledger; report zero exclusions rather than failing. */
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

    private static long countExcludedMinutes(Connection connection, HistoryTarget target,
                                             Instant fromInclusive, Instant toExclusive) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT snapshot_time_utc) FROM price_exclusion
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
