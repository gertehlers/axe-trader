package io.g3tech.axetrader.history;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Instant;

@ConfigurationProperties(prefix = "axe-trader.history-import")
public record HistoryImportProperties(
        boolean enabled,
        String mode,
        String epic,
        String resolution,
        Instant from,
        Instant to,
        Path stagingDatabase,
        Path stagingDirectory,
        Path activeDatabase,
        Path archive
) {

    private static final Path DEFAULT_ACTIVE_DATABASE = Path.of("data", "axe-trader.sqlite");
    private static final Path DEFAULT_ARCHIVE = Path.of("data", "axe-trader.sqlite.gz");
    private static final Path DEFAULT_STAGING_DIRECTORY = Path.of("data", ".staging");

    public HistoryImportProperties {
        activeDatabase = activeDatabase == null ? DEFAULT_ACTIVE_DATABASE : activeDatabase;
        archive = archive == null ? DEFAULT_ARCHIVE : archive;
        stagingDirectory = stagingDirectory == null ? DEFAULT_STAGING_DIRECTORY : stagingDirectory;
    }

    public Mode requiredMode() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalStateException("axe-trader.history-import.mode must be configured");
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown history import mode: " + mode, exception);
        }
    }

    public HistoryImportRequest request() {
        return new HistoryImportRequest(epic, resolution, from, to, requiredStagingDatabase(), "capital");
    }

    public Path requiredStagingDatabase() {
        if (stagingDatabase == null || stagingDatabase.toString().isBlank()) {
            throw new IllegalStateException("axe-trader.history-import.staging-database must be configured");
        }
        return stagingDatabase;
    }

    public enum Mode {
        PROBE,
        STAGE,
        PROMOTE,
        UPDATE,
        REPORT,
        ARCHIVE
    }
}
