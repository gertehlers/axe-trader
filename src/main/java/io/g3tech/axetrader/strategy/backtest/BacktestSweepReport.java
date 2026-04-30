package io.g3tech.axetrader.strategy.backtest;

import java.util.List;

public record BacktestSweepReport(
        List<BulkBacktestReport> reports
) {

    public BulkBacktestReport bestByTotalRiskMultiple() {
        return reports.stream()
                .max(java.util.Comparator.comparingDouble(BulkBacktestReport::totalRiskMultiple))
                .orElseThrow();
    }

    public BulkBacktestReport bestByAccuracy() {
        return reports.stream()
                .max(java.util.Comparator.comparingDouble(BulkBacktestReport::accuracy))
                .orElseThrow();
    }
}
