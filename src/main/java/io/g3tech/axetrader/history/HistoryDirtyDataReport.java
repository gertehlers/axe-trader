package io.g3tech.axetrader.history;

import java.time.Instant;
import java.util.Map;

public record HistoryDirtyDataReport(
        HistoryTarget target,
        Instant fromInclusive,
        Instant toExclusive,
        long storedMinutes,
        long excludedMinutes,
        double dirtyRate,
        Map<String, Long> exclusionsByReason
) {

    public HistoryDirtyDataReport {
        exclusionsByReason = Map.copyOf(exclusionsByReason);
    }
}
