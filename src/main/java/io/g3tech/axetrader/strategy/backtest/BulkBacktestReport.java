package io.g3tech.axetrader.strategy.backtest;

import java.time.Instant;
import java.util.Map;

public record BulkBacktestReport(
        String epic,
        String resolution,
        Instant from,
        Instant to,
        int candlesProcessed,
        int signalsDetected,
        int tradesClosed,
        double winRate,
        double totalRiskMultiple,
        double averageRiskMultiple,
        Map<ExitReason, Long> exitReasons
) {
}
