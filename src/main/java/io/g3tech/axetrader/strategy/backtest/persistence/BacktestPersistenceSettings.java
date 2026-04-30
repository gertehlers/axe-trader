package io.g3tech.axetrader.strategy.backtest.persistence;

public record BacktestPersistenceSettings(String databasePath) {

    public BacktestPersistenceSettings {
        if (databasePath == null || databasePath.isBlank()) {
            throw new IllegalArgumentException("databasePath must be configured");
        }
    }
}
