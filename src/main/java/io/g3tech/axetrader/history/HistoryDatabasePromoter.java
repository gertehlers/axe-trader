package io.g3tech.axetrader.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Replaces the active database only after a clean staging audit and a durable backup. */
public final class HistoryDatabasePromoter {

    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Clock clock;

    public HistoryDatabasePromoter() {
        this(Clock.systemUTC());
    }

    public HistoryDatabasePromoter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path promote(Path stagingDatabase, Path activeDatabase, HistoryImportAudit audit) {
        Objects.requireNonNull(stagingDatabase, "stagingDatabase");
        Objects.requireNonNull(activeDatabase, "activeDatabase");
        Objects.requireNonNull(audit, "audit");
        if (!audit.isPromotable()) {
            throw new IllegalStateException("refusing to promote a failed history import audit");
        }

        Path backup = activeDatabase.resolveSibling(
                activeDatabase.getFileName() + ".backup-" + BACKUP_TIMESTAMP.format(clock.instant()));
        try {
            Files.copy(activeDatabase, backup);
            moveStagingIntoPlace(stagingDatabase, activeDatabase);
            return backup;
        } catch (IOException exception) {
            throw new UncheckedIOException("could not promote staged history database", exception);
        }
    }

    private static void moveStagingIntoPlace(Path stagingDatabase, Path activeDatabase) throws IOException {
        try {
            Files.move(stagingDatabase, activeDatabase,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException atomicMoveNotSupported) {
            Files.move(stagingDatabase, activeDatabase, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
