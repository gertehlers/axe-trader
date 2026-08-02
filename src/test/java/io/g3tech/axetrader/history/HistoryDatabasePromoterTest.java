package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.CopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryDatabasePromoterTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:34:56Z");

    @TempDir
    Path tempDir;

    @Test
    void promotionBacksUpBothLegacyFilesAndRebuildsArchive() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = write("axe-trader.sqlite.gz", "legacy-archive");
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(Clock.fixed(NOW, ZoneOffset.UTC));

        Path activeBackup = promoter.promote(staging, active, archive, promotableAudit());

        assertThat(activeBackup.getFileName().toString()).contains("legacy-corrupt-20260802T123456Z");
        assertThat(Files.readString(activeBackup)).isEqualTo("legacy-database");
        Path archiveBackup = archive.resolveSibling(
                archive.getFileName() + ".legacy-corrupt-20260802T123456Z");
        assertThat(Files.readString(archiveBackup)).isEqualTo("legacy-archive");
        assertThat(Files.readString(active)).isEqualTo("clean-database");
        assertThat(staging).doesNotExist();
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            assertThat(input.readAllBytes()).isEqualTo("clean-database".getBytes());
        }
    }

    @Test
    void failedAuditLeavesEveryInputUntouchedAndCreatesNoBackup() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = write("axe-trader.sqlite.gz", "legacy-archive");
        HistoryImportAudit failedAudit = new HistoryImportAudit(
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T00:01:00Z"),
                null, null, 1, 1, 0, 1, 0, 0, Map.of(), false);
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> promoter.promote(staging, active, archive, failedAudit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit");

        assertThat(Files.readString(staging)).isEqualTo("clean-database");
        assertThat(Files.readString(active)).isEqualTo("legacy-database");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("stage.sqlite", "axe-trader.sqlite", "axe-trader.sqlite.gz");
        }
    }

    @Test
    void missingLegacyArchiveFailsBeforeActiveDatabaseChanges() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = tempDir.resolve("axe-trader.sqlite.gz");
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> promoter.promote(staging, active, archive, promotableAudit()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archive");

        assertThat(Files.readString(staging)).isEqualTo("clean-database");
        assertThat(Files.readString(active)).isEqualTo("legacy-database");
        assertThat(archive).doesNotExist();
    }

    @Test
    void archiveInstallFailureRestoresBothActiveFilesAndKeepsStagingRetryable() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = write("axe-trader.sqlite.gz", "legacy-archive");
        HistoryDatabasePromoter.FileMover mover = failFirstMoveTo(archive);
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(
                Clock.fixed(NOW, ZoneOffset.UTC), mover);

        assertThatThrownBy(() -> promoter.promote(staging, active, archive, promotableAudit()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promote");

        assertThat(Files.readString(active)).isEqualTo("legacy-database");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(Files.readString(staging)).isEqualTo("clean-database");
    }

    @Test
    void activeInstallFailureRestoresBothActiveFilesAndKeepsStagingRetryable() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = write("axe-trader.sqlite.gz", "legacy-archive");
        HistoryDatabasePromoter.FileMover mover = failFirstMoveTo(active);
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(
                Clock.fixed(NOW, ZoneOffset.UTC), mover);

        assertThatThrownBy(() -> promoter.promote(staging, active, archive, promotableAudit()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promote");

        assertThat(Files.readString(active)).isEqualTo("legacy-database");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(Files.readString(staging)).isEqualTo("clean-database");
    }

    @Test
    void atomicMoveFallbackCanCompletePromotion() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = write("axe-trader.sqlite.gz", "legacy-archive");
        HistoryDatabasePromoter.FileMover mover = (source, target, options) -> {
            if (containsAtomicMove(options)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test fallback");
            }
            return Files.move(source, target, options);
        };
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(
                Clock.fixed(NOW, ZoneOffset.UTC), mover);

        promoter.promote(staging, active, archive, promotableAudit());

        assertThat(Files.readString(active)).isEqualTo("clean-database");
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            assertThat(input.readAllBytes()).isEqualTo("clean-database".getBytes());
        }
    }

    @Test
    void archiveFallbackFailureRestoresBothActiveFilesAndKeepsStagingRetryable() throws Exception {
        Path staging = write("stage.sqlite", "clean-database");
        Path active = write("axe-trader.sqlite", "legacy-database");
        Path archive = write("axe-trader.sqlite.gz", "legacy-archive");
        boolean[] archiveFallbackFailed = {false};
        HistoryDatabasePromoter.FileMover mover = (source, target, options) -> {
            if (containsAtomicMove(options)) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test fallback");
            }
            if (target.equals(archive.toAbsolutePath().normalize()) && !archiveFallbackFailed[0]) {
                archiveFallbackFailed[0] = true;
                throw new IOException("injected archive fallback failure");
            }
            return Files.move(source, target, options);
        };
        HistoryDatabasePromoter promoter = new HistoryDatabasePromoter(
                Clock.fixed(NOW, ZoneOffset.UTC), mover);

        assertThatThrownBy(() -> promoter.promote(staging, active, archive, promotableAudit()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promote");

        assertThat(Files.readString(active)).isEqualTo("legacy-database");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(Files.readString(staging)).isEqualTo("clean-database");
    }

    private Path write(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static HistoryImportAudit promotableAudit() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant actual = Instant.parse("2024-01-01T00:00:00Z");
        return new HistoryImportAudit(from, Instant.parse("2024-01-01T00:01:00Z"),
                actual, actual, 1, 1, 0, 1, 0, 0, Map.of(), true);
    }

    private static HistoryDatabasePromoter.FileMover failFirstMoveTo(Path failedTarget) {
        Path normalizedTarget = failedTarget.toAbsolutePath().normalize();
        boolean[] failed = {false};
        return (source, target, options) -> {
            if (target.equals(normalizedTarget) && !failed[0]) {
                failed[0] = true;
                throw new IOException("injected move failure");
            }
            return Files.move(source, target, options);
        };
    }

    private static boolean containsAtomicMove(CopyOption[] options) {
        return java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE);
    }
}
