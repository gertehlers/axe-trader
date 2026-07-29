package io.g3tech.axetrader.backtest.discovery.model;

import java.time.Instant;
import java.util.Objects;

/** One executable, exit-side bar in an offline forward path. */
public record PathPoint(
        int index,
        Instant time,
        double openPrice,
        double highPrice,
        double lowPrice,
        double closePrice) {

    public PathPoint {
        Objects.requireNonNull(time, "time");
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        if (!Double.isFinite(openPrice) || !Double.isFinite(highPrice)
                || !Double.isFinite(lowPrice) || !Double.isFinite(closePrice)) {
            throw new IllegalArgumentException("path prices must be finite");
        }
    }
}
