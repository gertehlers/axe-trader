package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Aggregate development metrics used for reporting and promotion. */
public record ValidationSummary(
        String candidateId,
        int independentZones,
        List<MonthlyResult> monthlyResults,
        double totalNet,
        double medianMonthlyNet,
        double worstMonthlyNet,
        double maximumDrawdown,
        double netToMaximumDrawdown,
        Map<Direction, Double> totalNetByDirection,
        Set<Direction> enabledDirections,
        double profitableSampledMonthPercentage) {

    public ValidationSummary {
        Objects.requireNonNull(candidateId, "candidateId");
        monthlyResults = List.copyOf(Objects.requireNonNull(monthlyResults, "monthlyResults"));
        totalNetByDirection = Map.copyOf(Objects.requireNonNull(totalNetByDirection, "totalNetByDirection"));
        enabledDirections = Set.copyOf(Objects.requireNonNull(enabledDirections, "enabledDirections"));
        if (candidateId.isBlank() || independentZones < 0 || enabledDirections.isEmpty()
                || !Double.isFinite(totalNet) || maximumDrawdown < 0.0
                || (!Double.isFinite(netToMaximumDrawdown) && !Double.isInfinite(netToMaximumDrawdown))
                || (!Double.isFinite(profitableSampledMonthPercentage) && !Double.isNaN(profitableSampledMonthPercentage))) {
            throw new IllegalArgumentException("invalid validation summary");
        }
        if (!totalNetByDirection.keySet().containsAll(enabledDirections)) {
            throw new IllegalArgumentException("direction totals must cover every enabled direction");
        }
    }

    public List<MonthlyResult> sampledMonths() {
        return monthlyResults.stream().filter(MonthlyResult::sampled).toList();
    }
}
