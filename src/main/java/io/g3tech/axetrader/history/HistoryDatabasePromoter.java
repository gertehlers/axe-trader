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
    private final FileMover fileMover;

    public HistoryDatabasePromoter() {
        this(Clock.systemUTC(), Files::move);
    }

    HistoryDatabasePromoter(Clock clock) {
        this(clock, Files::move);
    }

    HistoryDatabasePromoter(Clock clock, FileMover fileMover) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fileMover = Objects.requireNonNull(fileMover, "fileMover");
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
        Path databaseTemp = null;
        Path archiveTemp = null;
        Path activeRollback;
        Path archiveRollback;
        try {
            databaseTemp = createDatabaseTemp(staging, active);
            archiveTemp = createGzipTemp(staging, gzArchive);
            activeRollback = reserveRollbackPath(active);
            archiveRollback = reserveRollbackPath(gzArchive);
        } catch (RuntimeException exception) {
            deleteIfCreated(databaseTemp, databaseTemp != null);
            deleteIfCreated(archiveTemp, archiveTemp != null);
            throw exception;
        }

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
            deleteIfCreated(databaseTemp, true);
            deleteIfCreated(archiveTemp, true);
            throw new IllegalStateException("Could not back up legacy history files", exception);
        }

        boolean activeParked = false;
        boolean archiveParked = false;
        boolean committed = false;
        try {
            moveAtomicallyWithReplaceFallback(active, activeRollback);
            activeParked = true;
            moveAtomicallyWithReplaceFallback(gzArchive, archiveRollback);
            archiveParked = true;
            moveAtomicallyWithReplaceFallback(databaseTemp, active);
            moveAtomicallyWithReplaceFallback(archiveTemp, gzArchive);
            committed = true;
            return activeBackup;
        } catch (IOException exception) {
            rollback(active, activeRollback, activeParked, gzArchive, archiveRollback, archiveParked, exception);
            throw new IllegalStateException("Could not promote staged history database", exception);
        } finally {
            deleteIfCreated(databaseTemp, true);
            deleteIfCreated(archiveTemp, true);
            if (committed) {
                deleteIfCreated(activeRollback, true);
                deleteIfCreated(archiveRollback, true);
                deleteIfCreated(staging, true);
            }
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

    private static Path createDatabaseTemp(Path source, Path active) {
        Path parent = parentOf(active);
        try {
            Path temp = Files.createTempFile(parent, active.getFileName() + ".new-", ".tmp");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                Files.deleteIfExists(temp);
                throw exception;
            }
            return temp;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare promoted history database", exception);
        }
    }

    private static Path createGzipTemp(Path source, Path archive) {
        Path parent = parentOf(archive);
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

    private static Path reserveRollbackPath(Path target) {
        Path parent = parentOf(target);
        try {
            Path rollback = Files.createTempFile(parent, target.getFileName() + ".rollback-", ".tmp");
            Files.delete(rollback);
            return rollback;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reserve history promotion rollback path", exception);
        }
    }

    private static Path parentOf(Path path) {
        return path.getParent() == null ? Path.of(".").toAbsolutePath() : path.getParent();
    }

    private void moveAtomicallyWithReplaceFallback(Path source, Path target) throws IOException {
        try {
            fileMover.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            fileMover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rollback(Path active, Path activeRollback, boolean activeParked,
                          Path archive, Path archiveRollback, boolean archiveParked,
                          IOException promotionFailure) {
        if (archiveParked) {
            restore(archiveRollback, archive, promotionFailure);
        }
        if (activeParked) {
            restore(activeRollback, active, promotionFailure);
        }
    }

    private void restore(Path rollback, Path target, IOException promotionFailure) {
        try {
            moveAtomicallyWithReplaceFallback(rollback, target);
        } catch (IOException rollbackFailure) {
            promotionFailure.addSuppressed(rollbackFailure);
        }
    }

    private static void deleteIfCreated(Path path, boolean created) {
        if (!created || path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface FileMover {
        Path move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException;
    }
}
