package io.g3tech.axetrader.history;

import java.time.Instant;

public record HistoryUpdateOutcome(
        HistoryTarget target,
        Status status,
        Instant fromInclusive,
        Instant toExclusive,
        HistoryImportAudit audit,
        HistoryDeltaMerger.MergeResult merge,
        String failure
) {

    public static HistoryUpdateOutcome alreadyCurrent(HistoryTarget target) {
        return new HistoryUpdateOutcome(target, Status.ALREADY_CURRENT, null, null, null, null, null);
    }

    public static HistoryUpdateOutcome merged(HistoryTarget target, Instant from, Instant to,
                                              HistoryImportAudit audit, HistoryDeltaMerger.MergeResult merge) {
        return new HistoryUpdateOutcome(target, Status.MERGED, from, to, audit, merge, null);
    }

    public static HistoryUpdateOutcome failed(HistoryTarget target, Instant from, Instant to, String failure) {
        return new HistoryUpdateOutcome(target, Status.FAILED, from, to, null, null, failure);
    }

    public enum Status {
        ALREADY_CURRENT,
        MERGED,
        FAILED
    }
}
