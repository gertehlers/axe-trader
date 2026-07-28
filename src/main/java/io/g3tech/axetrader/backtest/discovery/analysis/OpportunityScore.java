package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;

import java.util.Objects;

/**
 * A versioned, offline-only score. The joined observation is retained so zone and report consumers
 * can always reach the unmodified forward label behind the derived percentiles.
 */
public record OpportunityScore(
        LabelledObservation labelledObservation,
        double netMfeAtrPercentile,
        double rewardToRiskPercentile,
        double directionalEfficiencyPercentile,
        double timeToMfePercentile,
        double twoSidedExcursionPercentile,
        double compositeScore,
        OpportunityClass opportunityClass,
        String scoreVersion) {

    public OpportunityScore {
        Objects.requireNonNull(labelledObservation, "labelledObservation");
        Objects.requireNonNull(opportunityClass, "opportunityClass");
        Objects.requireNonNull(scoreVersion, "scoreVersion");
        percentile(netMfeAtrPercentile, "netMfeAtrPercentile");
        percentile(rewardToRiskPercentile, "rewardToRiskPercentile");
        percentile(directionalEfficiencyPercentile, "directionalEfficiencyPercentile");
        percentile(timeToMfePercentile, "timeToMfePercentile");
        percentile(twoSidedExcursionPercentile, "twoSidedExcursionPercentile");
        percentile(compositeScore, "compositeScore");
        if (scoreVersion.isBlank()) {
            throw new IllegalArgumentException("scoreVersion must not be blank");
        }
    }

    public OpportunityScore(
            LabelledObservation labelledObservation,
            double netMfeAtrPercentile,
            double rewardToRiskPercentile,
            double directionalEfficiencyPercentile,
            double timeToMfePercentile,
            double twoSidedExcursionPercentile,
            OpportunityClass opportunityClass,
            String scoreVersion) {
        this(
                labelledObservation,
                netMfeAtrPercentile,
                rewardToRiskPercentile,
                directionalEfficiencyPercentile,
                timeToMfePercentile,
                twoSidedExcursionPercentile,
                average(
                        netMfeAtrPercentile,
                        rewardToRiskPercentile,
                        directionalEfficiencyPercentile,
                        timeToMfePercentile,
                        twoSidedExcursionPercentile),
                opportunityClass,
                scoreVersion);
    }

    private static double average(double... values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    private static void percentile(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be a finite value from zero through one");
        }
    }
}
