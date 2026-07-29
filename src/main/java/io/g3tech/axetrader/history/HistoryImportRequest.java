package io.g3tech.axetrader.history;

import io.g3tech.axetrader.backtest.discovery.DiscoveryWindowPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Immutable definition of a bounded historical-price import. */
public record HistoryImportRequest(
        String epic,
        String resolution,
        Instant from,
        Instant to,
        Path stagingDatabase,
        String source,
        boolean discoveryWindow) {

    public HistoryImportRequest {
        if (epic == null || epic.isBlank()) {
            throw new IllegalArgumentException("epic must be configured");
        }
        if (resolution == null || resolution.isBlank()) {
            throw new IllegalArgumentException("resolution must be configured");
        }
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (stagingDatabase == null) {
            throw new IllegalArgumentException("stagingDatabase must be configured");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must be configured");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("history import requires a non-empty half-open window");
        }
        if (discoveryWindow) {
            new DiscoveryWindowPolicy().requireDevelopmentWindow(from, to);
        }
    }
}
