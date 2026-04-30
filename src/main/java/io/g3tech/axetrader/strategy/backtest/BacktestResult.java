package io.g3tech.axetrader.strategy.backtest;

import java.util.List;

public record BacktestResult(
        int candlesProcessed,
        int warmupCandles,
        List<BacktestEvent> events
) {

    public BacktestResult {
        events = List.copyOf(events);
    }

    public int signalsDetected() {
        return events.size();
    }
}
