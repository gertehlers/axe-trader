package io.g3tech.axetrader.strategy.backtest;

import java.util.List;

public record BacktestResult(
        int candlesProcessed,
        int warmupCandles,
        List<BacktestEvent> events,
        List<BacktestTrade> trades
) {

    public BacktestResult {
        events = List.copyOf(events);
        trades = List.copyOf(trades);
    }

    public int signalsDetected() {
        return events.size();
    }

    public int tradesClosed() {
        return trades.size();
    }

    public double totalRiskMultiple() {
        return trades.stream()
                .mapToDouble(BacktestTrade::riskMultiple)
                .sum();
    }

    public double winRate() {
        if (trades.isEmpty()) {
            return 0;
        }

        var winners = trades.stream()
                .filter(BacktestTrade::isWinner)
                .count();

        return (double) winners / trades.size();
    }
}
