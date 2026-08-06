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

    /**
     * The legacy dataset stored minutes as {@code 2024-12-04T23:20Z}, which sorts differently from the
     * clean {@code 2024-12-04T23:20:00Z} form. {@code MAX()} across mixed formats returns the wrong
     * minute, so reject anything that is not already canonical rather than guess.
     */
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
