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
        long observationCount,
        long rawReceivedCount,
        long rawAcceptedCount,
        long rawRejectedCount,
        long pendingWorkCount,
        boolean isConsistent
) {

    public HistoryImportAudit(
            Instant requestedFrom, Instant requestedTo, Instant actualFrom, Instant actualTo,
            long receivedCount, long acceptedCount, long rejectedCount, long acceptedMinuteCount,
            long excludedMinuteCount, long duplicateCount, Map<String, Long> exclusionsByReason,
            List<HistoryCoverageGap> recognizedSessionClosures, List<HistoryCoverageGap> continuityGaps,
            boolean isConsistent) {
        this(requestedFrom, requestedTo, actualFrom, actualTo, receivedCount, acceptedCount, rejectedCount,
                acceptedMinuteCount, excludedMinuteCount, duplicateCount, exclusionsByReason,
                recognizedSessionClosures, continuityGaps, 0, receivedCount, acceptedCount, rejectedCount, 0,
                isConsistent);
    }

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
