package io.g3tech.axetrader.history;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

@Service
public class HistoryDatabasePromoter {

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public HistoryDatabasePromoter() {
        this(Clock.systemUTC());
    }

    HistoryDatabasePromoter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path promote(Path stagingDatabase, Path activeDatabase, Path archive, HistoryImportAudit audit) {
        requirePromotableAudit(audit);
        Path staging = normalized(stagingDatabase, "stagingDatabase");
        Path active = normalized(activeDatabase, "activeDatabase");
        Path gzArchive = normalized(archive, "archive");
        if (staging.equals(active) || staging.equals(gzArchive) || active.equals(gzArchive)) {
            throw new IllegalArgumentException("Staging, active, and archive paths must be different");
        }
        requireRegularFile(staging, "Staging database");
        requireRegularFile(active, "Active database");
        requireRegularFile(gzArchive, "Active archive");

        String suffix = ".legacy-corrupt-" + BACKUP_TIMESTAMP.format(clock.instant());
        Path activeBackup = active.resolveSibling(active.getFileName() + suffix);
        Path archiveBackup = gzArchive.resolveSibling(gzArchive.getFileName() + suffix);
        Path archiveTemp = createGzipTemp(staging, gzArchive);

        boolean activeBackupCreated = false;
        boolean archiveBackupCreated = false;
        try {
            Files.copy(active, activeBackup);
            activeBackupCreated = true;
            Files.copy(gzArchive, archiveBackup);
            archiveBackupCreated = true;
        } catch (IOException exception) {
            deleteIfCreated(archiveBackup, archiveBackupCreated);
            deleteIfCreated(activeBackup, activeBackupCreated);
            deleteIfCreated(archiveTemp, true);
            throw new IllegalStateException("Could not back up legacy history files", exception);
        }

        try {
            moveAtomicallyWithReplaceFallback(staging, active);
            moveAtomicallyWithReplaceFallback(archiveTemp, gzArchive);
            return activeBackup;
        } catch (IOException exception) {
            deleteIfCreated(archiveTemp, true);
            throw new IllegalStateException("Could not promote staged history database", exception);
        }
    }

    static void requirePromotableAudit(HistoryImportAudit audit) {
        Objects.requireNonNull(audit, "audit");
        boolean countsReconcile = audit.receivedCount() == audit.acceptedCount() + audit.rejectedCount();
        boolean acceptedRowsReconcile = audit.acceptedCount() == audit.acceptedMinuteCount() + audit.duplicateCount();
        if (!audit.isConsistent() || audit.duplicateCount() != 0 || !countsReconcile || !acceptedRowsReconcile
                || audit.acceptedMinuteCount() == 0) {
            throw new IllegalStateException("Refusing to promote a failed history import audit");
        }
    }

    private static Path normalized(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static void requireRegularFile(Path path, String description) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(description + " does not exist: " + path);
        }
    }

    private static Path createGzipTemp(Path source, Path archive) {
        Path parent = archive.getParent() == null ? Path.of(".").toAbsolutePath() : archive.getParent();
        try {
            Path temp = Files.createTempFile(parent, archive.getFileName() + ".", ".tmp");
            try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(temp))) {
                Files.copy(source, output);
            } catch (IOException exception) {
                Files.deleteIfExists(temp);
                throw exception;
            }
            return temp;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not build promoted history archive", exception);
        }
    }

    private static void moveAtomicallyWithReplaceFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteIfCreated(Path path, boolean created) {
        if (!created) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
