package io.g3tech.axetrader.history;

import java.nio.file.Path;
import java.time.Instant;

public record HistoryImportRequest(
        String epic,
        String resolution,
        Instant from,
        Instant to,
        Path stagingDatabase,
        String source
) {

    public HistoryImportRequest {
        requireNonBlank(epic, "epic");
        if (!"MINUTE".equals(resolution)) {
            throw new IllegalArgumentException("resolution must be MINUTE");
        }
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (from.getEpochSecond() % 60 != 0 || to.getEpochSecond() % 60 != 0
                || from.getNano() != 0 || to.getNano() != 0) {
            throw new IllegalArgumentException("from and to must be aligned to whole UTC minutes");
        }
        if (stagingDatabase == null) {
            throw new IllegalArgumentException("stagingDatabase must be configured");
        }
        requireNonBlank(source, "source");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
    }
}
