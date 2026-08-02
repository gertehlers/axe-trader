package io.g3tech.axetrader.history;

import java.math.BigDecimal;
import java.time.Instant;

public record ImportedPrice(
        Instant timestamp,
        BigDecimal openBid,
        BigDecimal openAsk,
        BigDecimal highBid,
        BigDecimal highAsk,
        BigDecimal lowBid,
        BigDecimal lowAsk,
        BigDecimal closeBid,
        BigDecimal closeAsk,
        Long lastTradedVolume
) {
}
