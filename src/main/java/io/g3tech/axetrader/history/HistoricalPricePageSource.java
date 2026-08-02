package io.g3tech.axetrader.history;

import java.time.Instant;

public interface HistoricalPricePageSource {

    ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars);
}
