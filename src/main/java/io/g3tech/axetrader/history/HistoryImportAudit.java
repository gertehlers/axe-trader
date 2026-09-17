package io.g3tech.axetrader.history;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Audit of one staged import.
 *
 * <p>{@code providerEmptyIntervals} are intervals for which Capital.com returned no bars (empty body or
 * 404). They are <strong>not</strong> verified market closures — the provider response cannot tell a
 * closure from a data hole. Closure classification happens in the Python data verification report
 * using Capital.com trading hours.
 */
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
        List<HistoryCoverageGap> providerEmptyIntervals,
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
            List<HistoryCoverageGap> providerEmptyIntervals, List<HistoryCoverageGap> continuityGaps,
            boolean isConsistent) {
        this(requestedFrom, requestedTo, actualFrom, actualTo, receivedCount, acceptedCount, rejectedCount,
                acceptedMinuteCount, excludedMinuteCount, duplicateCount, exclusionsByReason,
                providerEmptyIntervals, continuityGaps, 0, receivedCount, acceptedCount, rejectedCount, 0,
                isConsistent);
    }

    public HistoryImportAudit {
        exclusionsByReason = Map.copyOf(exclusionsByReason);
        providerEmptyIntervals = List.copyOf(providerEmptyIntervals);
        continuityGaps = List.copyOf(continuityGaps);
    }

    public long providerEmptyMinuteCount() {
        return providerEmptyIntervals.stream()
                .mapToLong(gap -> Duration.between(gap.fromInclusive(), gap.toExclusive()).toMinutes())
                .sum();
    }

    public long continuityGapMinuteCount() {
        return continuityGaps.stream()
                .mapToLong(gap -> Duration.between(gap.fromInclusive(), gap.toExclusive()).toMinutes())
                .sum();
    }
}
