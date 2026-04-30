package io.g3tech.axetrader.strategy.backtest.data;

public enum HistoricalDataSourceMode {
    CAPITAL,
    FAKE,
    FALLBACK;

    public static HistoricalDataSourceMode from(String value) {
        if (value == null || value.isBlank()) {
            return FALLBACK;
        }

        return HistoricalDataSourceMode.valueOf(value.trim().replace('-', '_').toUpperCase());
    }
}
