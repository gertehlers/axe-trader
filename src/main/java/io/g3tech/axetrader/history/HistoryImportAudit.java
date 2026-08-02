package io.g3tech.axetrader.history;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
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
        List<HistoryCoverageGap> recognizedSessionClosures,
        List<HistoryCoverageGap> continuityGaps,
        boolean isConsistent
) {

    public HistoryImportAudit {
        exclusionsByReason = Map.copyOf(exclusionsByReason);
        recognizedSessionClosures = List.copyOf(recognizedSessionClosures);
        continuityGaps = List.copyOf(continuityGaps);
    }

    public long recognizedClosureMinuteCount() {
        return recognizedSessionClosures.stream()
                .mapToLong(gap -> Duration.between(gap.fromInclusive(), gap.toExclusive()).toMinutes())
                .sum();
    }

    public long continuityGapMinuteCount() {
        return continuityGaps.stream()
                .mapToLong(gap -> Duration.between(gap.fromInclusive(), gap.toExclusive()).toMinutes())
                .sum();
    }
}
