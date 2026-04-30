package io.g3tech.axetrader.strategy;

public record IndicatorSettings(
        int fastEmaPeriod,
        int slowEmaPeriod,
        int rsiPeriod,
        int atrPeriod,
        int adxPeriod
) {

    public IndicatorSettings {
        if (fastEmaPeriod <= 0) {
            throw new IllegalArgumentException("Fast EMA period must be positive");
        }
        if (slowEmaPeriod <= 0) {
            throw new IllegalArgumentException("Slow EMA period must be positive");
        }
        if (rsiPeriod <= 0) {
            throw new IllegalArgumentException("RSI period must be positive");
        }
        if (atrPeriod <= 0) {
            throw new IllegalArgumentException("ATR period must be positive");
        }
        if (adxPeriod <= 0) {
            throw new IllegalArgumentException("ADX period must be positive");
        }
    }

    public int requiredCandles() {
        return Math.max(Math.max(fastEmaPeriod, slowEmaPeriod), (adxPeriod * 2) + 1) + 1;
    }

    public static IndicatorSettings defaults() {
        return new IndicatorSettings(20, 50, 14, 14, 14);
    }
}
