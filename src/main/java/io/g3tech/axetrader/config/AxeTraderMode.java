package io.g3tech.axetrader.config;

public enum AxeTraderMode {
    BACKTEST,
    MONITOR,
    TRADE;

    public static AxeTraderMode from(String value) {
        if (value == null || value.isBlank()) {
            return MONITOR;
        }

        return AxeTraderMode.valueOf(value.trim().replace('-', '_').toUpperCase());
    }

    public boolean isLiveMarketMode() {
        return this == MONITOR || this == TRADE;
    }
}
