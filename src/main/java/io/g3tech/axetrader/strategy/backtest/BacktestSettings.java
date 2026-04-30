package io.g3tech.axetrader.strategy.backtest;

public record BacktestSettings(
        int minimumCandles,
        double targetRiskMultiple,
        double stopAtrMultiple
) {

    public BacktestSettings(int minimumCandles, double targetRiskMultiple) {
        this(minimumCandles, targetRiskMultiple, 1.5);
    }

    public BacktestSettings {
        if (minimumCandles <= 1) {
            throw new IllegalArgumentException("Minimum candles must be greater than one");
        }

        if (targetRiskMultiple <= 0) {
            throw new IllegalArgumentException("Target risk multiple must be positive");
        }

        if (stopAtrMultiple <= 0) {
            throw new IllegalArgumentException("Stop ATR multiple must be positive");
        }
    }

    public static BacktestSettings defaults() {
        return new BacktestSettings(60, 2.0, 1.5);
    }
}
