package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void successfulPromotionCreatesReadableBackupBeforeReplacingActiveDatabase(@TempDir Path directory) throws IOException {
        Path staging = directory.resolve("staging.sqlite");
        Path active = directory.resolve("active.sqlite");
        Files.writeString(staging, "staged database");
        Files.writeString(active, "active database");

        Path backup = promoter().promote(staging, active, CLEAN_AUDIT);

        assertThat(Files.readString(active)).isEqualTo("staged database");
        assertThat(Files.readString(backup)).isEqualTo("active database");
        assertThat(backup.getFileName().toString()).isEqualTo("active.sqlite.backup-20250120T162000Z");
        assertThat(Files.exists(staging)).isFalse();
    }

    private static HistoryDatabasePromoter promoter() {
        return new HistoryDatabasePromoter(Clock.fixed(Instant.parse("2025-01-20T16:20:00Z"), ZoneOffset.UTC));
    }
}
