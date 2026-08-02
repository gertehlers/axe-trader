package io.g3tech.axetrader.history;

import java.time.Instant;

public record HistoryCoverageGap(Instant fromInclusive, Instant toExclusive, String provenance) {

    public HistoryCoverageGap(Instant fromInclusive, Instant toExclusive) {
        this(fromInclusive, toExclusive, null);
    }

    public HistoryCoverageGap {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Coverage gap bounds must be non-empty");
        }
    }
}
