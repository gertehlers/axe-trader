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
