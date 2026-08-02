package io.g3tech.axetrader.history;

import java.time.Instant;

public record HistoryCoverageGap(Instant fromInclusive, Instant toExclusive) {

    public HistoryCoverageGap {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Coverage gap bounds must be non-empty");
        }
    }
}
