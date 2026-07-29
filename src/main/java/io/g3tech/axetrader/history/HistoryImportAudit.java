package io.g3tech.axetrader.history;

/** Summary of the validation queries run against one staged history import. */
public record HistoryImportAudit(
        long rowCount,
        long distinctTimestampCount,
        long duplicateCount,
        long invalidFieldCount,
        long coverageGapCount,
        long crossedOpenCount,
        long crossedHighCount,
        long crossedLowCount,
        long crossedCloseCount) {

    public boolean isPromotable() {
        return rowCount > 0
                && duplicateCount == 0
                && invalidFieldCount == 0
                && coverageGapCount == 0
                && crossedOpenCount == 0
                && crossedHighCount == 0
                && crossedLowCount == 0
                && crossedCloseCount == 0;
    }
}
