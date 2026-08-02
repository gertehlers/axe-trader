package io.g3tech.axetrader.history;

import java.time.Instant;
import java.util.Map;

public record HistoryImportAudit(
        Instant requestedFrom,
        Instant requestedTo,
        Instant actualFrom,
        Instant actualTo,
        long receivedCount,
        long acceptedCount,
        long rejectedCount,
        long acceptedMinuteCount,
        long excludedMinuteCount,
        long duplicateCount,
        Map<String, Long> exclusionsByReason,
        boolean isConsistent
) {

    public HistoryImportAudit {
        exclusionsByReason = Map.copyOf(exclusionsByReason);
    }
}
