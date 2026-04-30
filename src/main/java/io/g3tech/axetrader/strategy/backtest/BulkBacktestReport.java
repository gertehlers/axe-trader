package io.g3tech.axetrader.strategy.backtest;

import java.time.Instant;
import java.util.Map;

public record BulkBacktestReport(
        long runId,
        String epic,
        String resolution,
        double targetRiskMultiple,
        double stopAtrMultiple,
        Instant from,
        Instant to,
        int candlesProcessed,
        int signalsDetected,
        int tradesClosed,
        double winRate,
        int resolvedSignals,
        int correctSignals,
        int incorrectSignals,
        int unresolvedSignals,
        double accuracy,
        double longAccuracy,
        double shortAccuracy,
        Map<String, AccuracySummary> accuracyByDirection,
        Map<String, AccuracySummary> accuracyByVolatilityRegime,
        Map<String, AccuracySummary> accuracyByTrendRegime,
        Map<String, AccuracySummary> accuracyByScore,
        Map<String, AccuracySummary> accuracyByReason,
        Map<String, ForwardMovementSummary> forwardMovementByHorizon,
        ProfileReport profileReport,
        StrategyCandidateGate candidateGate,
        double totalRiskMultiple,
        double averageRiskMultiple,
        Map<ExitReason, Long> exitReasons
) {
}
