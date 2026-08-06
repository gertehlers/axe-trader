package io.g3tech.axetrader.history;

public record HistoryTarget(String source, String epic, String resolution) {

    public HistoryTarget {
        requireNonBlank(source, "source");
        requireNonBlank(epic, "epic");
        requireNonBlank(resolution, "resolution");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
    }
}
