package io.g3tech.axetrader.history;

import java.time.Instant;
import java.util.List;

public record ImportedPage(
        Instant requestedFrom,
        Instant requestedTo,
        List<ImportedPrice> prices,
        String payloadHash
) {

    public ImportedPage {
        if (requestedFrom == null || requestedTo == null || !requestedFrom.isBefore(requestedTo)) {
            throw new IllegalArgumentException("requestedFrom must be before requestedTo");
        }
        prices = List.copyOf(prices);
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("payloadHash must be configured");
        }
    }
}
