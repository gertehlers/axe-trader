package io.g3tech.axetrader.history;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface HistoricalPricePageSource {

    ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars);
}

record ImportedPage(Instant requestedFrom, Instant requestedTo, String payloadHash, List<HistoricalPrice> prices) {

    ImportedPage {
        Objects.requireNonNull(requestedFrom, "requestedFrom");
        Objects.requireNonNull(requestedTo, "requestedTo");
        Objects.requireNonNull(payloadHash, "payloadHash");
        prices = List.copyOf(Objects.requireNonNull(prices, "prices"));
    }
}
