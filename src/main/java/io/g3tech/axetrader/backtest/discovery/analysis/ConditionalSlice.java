package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.runner.Direction;

import java.util.List;
import java.util.Objects;

/** A transparent one-clause outcome comparison against the complete directional baseline. */
public record ConditionalSlice(
        Direction direction,
        String feature,
        RuleClause.Operator operator,
        double threshold,
        double meanNetMfeAtr,
        double baselineMeanNetMfeAtr,
        int independentZones,
        List<String> monthlyCoverage) {

    public ConditionalSlice {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(operator, "operator");
        monthlyCoverage = List.copyOf(Objects.requireNonNull(monthlyCoverage, "monthlyCoverage"));
        if (feature.isBlank() || !Double.isFinite(threshold) || !Double.isFinite(meanNetMfeAtr)
                || !Double.isFinite(baselineMeanNetMfeAtr) || independentZones < 0) {
            throw new IllegalArgumentException("invalid conditional slice");
        }
    }

    public double netMfeDeltaAtr() {
        return meanNetMfeAtr - baselineMeanNetMfeAtr;
    }
}
