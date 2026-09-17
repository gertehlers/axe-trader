package io.g3tech.axetrader.history;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One minute bar. {@code timestamp} is Capital.com's {@code snapshotTimeUTC}: the bar's <strong>open</strong>
 * minute in UTC. Verified 2026-09-17 on the demo API: {@code from=12:00, to=12:02} returned bars stamped
 * 12:00, 12:01 and 12:02 (to is inclusive), so a bar stamped 12:00 covers [12:00, 12:01).
 */
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
