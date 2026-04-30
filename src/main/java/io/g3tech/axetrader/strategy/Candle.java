package io.g3tech.axetrader.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Candle(
        Instant openedAt,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume
) {

    public Candle {
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");

        if (high.compareTo(low) < 0) {
            throw new IllegalArgumentException("Candle high must be greater than or equal to low");
        }

        if (open.compareTo(low) < 0 || open.compareTo(high) > 0) {
            throw new IllegalArgumentException("Candle open must be inside the candle range");
        }

        if (close.compareTo(low) < 0 || close.compareTo(high) > 0) {
            throw new IllegalArgumentException("Candle close must be inside the candle range");
        }
    }

    public double openValue() {
        return open.doubleValue();
    }

    public double highValue() {
        return high.doubleValue();
    }

    public double lowValue() {
        return low.doubleValue();
    }

    public double closeValue() {
        return close.doubleValue();
    }

    public double range() {
        return high.subtract(low).doubleValue();
    }

    public double closeLocation() {
        var range = range();
        if (range == 0) {
            return 0.5;
        }

        return close.subtract(low).doubleValue() / range;
    }

    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }

    public boolean isBearish() {
        return close.compareTo(open) < 0;
    }
}
