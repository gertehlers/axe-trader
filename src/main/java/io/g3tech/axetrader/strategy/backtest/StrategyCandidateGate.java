package io.g3tech.axetrader.strategy.backtest;

public record StrategyCandidateGate(
        double requiredAccuracy,
        int minimumResolvedSignals,
        boolean meetsAccuracyGate,
        boolean meetsProfitGate,
        boolean meetsSampleSizeGate,
        boolean candidateStrategy
) {

    public static StrategyCandidateGate evaluate(BulkBacktestReport report, double requiredAccuracy, int minimumResolvedSignals) {
        var meetsAccuracyGate = report.accuracy() >= requiredAccuracy;
        var meetsProfitGate = report.totalRiskMultiple() > 0 && report.averageRiskMultiple() > 0;
        var meetsSampleSizeGate = report.resolvedSignals() >= minimumResolvedSignals;

        return new StrategyCandidateGate(
                requiredAccuracy,
                minimumResolvedSignals,
                meetsAccuracyGate,
                meetsProfitGate,
                meetsSampleSizeGate,
                meetsAccuracyGate && meetsProfitGate && meetsSampleSizeGate
        );
    }
}
