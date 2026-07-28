package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict development gate before a candidate may spend the one-shot final OOS window. */
public final class PromotionGate {

    public GateResult evaluate(ValidationSummary summary) {
        Objects.requireNonNull(summary, "summary");
        List<String> failures = new ArrayList<>();
        if (!isFinitePositive(summary.totalNet())) {
            failures.add("total net must be positive");
        }
        if (!isFinitePositive(summary.medianMonthlyNet())) {
            failures.add("median monthly net must be positive");
        }
        if (!isFiniteNonNegative(summary.maximumDrawdown())) {
            failures.add("maximum drawdown must be finite and non-negative");
        }
        if (!Double.isFinite(summary.profitableSampledMonthPercentage())
                || summary.profitableSampledMonthPercentage() < 70.0) {
            failures.add("at least 70% of sampled months must be profitable");
        }
        if (summary.sampledMonths().size() < 6) {
            failures.add("at least six sampled months are required");
        }
        if (summary.independentZones() < 30) {
            failures.add("at least 30 independent development zones are required");
        }
        if (!(summary.netToMaximumDrawdown() >= 1.0)) {
            failures.add("net to maximum drawdown must be at least 1.0");
        }
        for (Direction direction : summary.enabledDirections()) {
            if (!isFinitePositive(summary.totalNetByDirection().get(direction))) {
                failures.add("enabled " + direction + " direction must have positive total net");
            }
        }
        return new GateResult(failures.isEmpty(), List.copyOf(failures));
    }

    private static boolean isFinitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean isFiniteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    public record GateResult(boolean passed, List<String> failures) {
        public GateResult {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }
    }
}
