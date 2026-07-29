package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryDatabasePromoterTest {

    private static final HistoryImportAudit FAILED_AUDIT = new HistoryImportAudit(1, 1, 0, 1, 0, 0, 0, 0, 0);
    private static final HistoryImportAudit CLEAN_AUDIT = new HistoryImportAudit(1, 1, 0, 0, 0, 0, 0, 0, 0);

    @Test
    void failedAuditLeavesActiveBytesUnchanged(@TempDir Path directory) throws IOException {
        Path staging = directory.resolve("staging.sqlite");
        Path active = directory.resolve("active.sqlite");
        Files.writeString(staging, "staged database");
        Files.writeString(active, "active database");

        assertThatIllegalStateException().isThrownBy(() -> promoter().promote(staging, active, FAILED_AUDIT));

        assertThat(Files.readString(active)).isEqualTo("active database");
        assertThat(Files.exists(staging)).isTrue();
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.filter(path -> path.getFileName().toString().contains("backup")).toList()).isEmpty();
        }
    }

    @Test
    void successfulPromotionCreatesReadableBackupBeforeReplacingActiveDatabase(@TempDir Path directory) throws Exception {
        Path staging = directory.resolve("staging.sqlite");
        Path active = directory.resolve("active.sqlite");
        writeSqliteDatabase(staging, "staged database");
        writeSqliteDatabase(active, "active database");

        Path backup = promoter().promote(staging, active, CLEAN_AUDIT);

        assertThat(readSqliteValue(active)).isEqualTo("staged database");
        assertThat(readSqliteValue(backup)).isEqualTo("active database");
        assertThat(backup.getFileName().toString()).isEqualTo("active.sqlite.backup-20250120T162000Z");
        assertThat(Files.exists(staging)).isFalse();
    }

    private static HistoryDatabasePromoter promoter() {
        return new HistoryDatabasePromoter(Clock.fixed(Instant.parse("2025-01-20T16:20:00Z"), ZoneOffset.UTC));
    }

    private static void writeSqliteDatabase(Path database, String value) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE import_test (value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO import_test (value) VALUES ('" + value + "')");
        }
    }

    private static String readSqliteValue(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM import_test")) {
            result.next();
            return result.getString(1);
        }
    }
}
