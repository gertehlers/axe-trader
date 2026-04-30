package io.g3tech.axetrader.strategy;

public record IndicatorSnapshot(
        double ema20,
        double ema50,
        double ema20Slope,
        double rsi14,
        double atr14,
        double adx14,
        double plusDi14,
        double minusDi14
) {
}
