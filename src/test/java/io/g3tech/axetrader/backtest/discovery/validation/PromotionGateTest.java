package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionGateTest {

    @Test
    void requiresEveryPromotionThreshold() {
        assertThat(new PromotionGate().evaluate(qualifyingSummary()).passed()).isTrue();
        assertThat(new PromotionGate().evaluate(summary(29, 10, 1, 100,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0))).passed()).isFalse();
        assertThat(new PromotionGate().evaluate(summary(30, 0, 1, 100,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0))).passed()).isFalse();
        assertThat(new PromotionGate().evaluate(summary(30, 10, 0, 100,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0))).passed()).isFalse();
        assertThat(new PromotionGate().evaluate(summary(30, 10, 1, 69,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0))).passed()).isFalse();
        assertThat(new PromotionGate().evaluate(summary(30, 10, .99, 100,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0))).passed()).isFalse();
        assertThat(new PromotionGate().evaluate(summary(30, 10, 1, 100,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0), 5)).passed()).isFalse();
        assertThat(new PromotionGate().evaluate(summary(30, 10, 1, 100,
                List.of(Direction.LONG), Map.of(Direction.LONG, 10.0), 6, 9)).passed()).isFalse();
    }

    @Test
    void rejectsCombinedCandidateWhenAnEnabledDirectionHasNoPositiveTotalNet() {
        ValidationSummary summary = summary(30, 10, 1, 100, List.of(Direction.LONG, Direction.SHORT),
                Map.of(Direction.LONG, 10.0, Direction.SHORT, 0.0));

        assertThat(new PromotionGate().evaluate(summary).passed()).isFalse();
    }

    private static ValidationSummary qualifyingSummary() {
        return summary(30, 10, 1, 100, List.of(Direction.LONG), Map.of(Direction.LONG, 10.0));
    }

    private static ValidationSummary summary(
            int zones, double total, double ratio, double profitablePercentage,
            List<Direction> directions, Map<Direction, Double> directionNet) {
        return summary(zones, total, ratio, profitablePercentage, directions, directionNet, 6, 10);
    }

    private static ValidationSummary summary(
            int zones, double total, double ratio, double profitablePercentage,
            List<Direction> directions, Map<Direction, Double> directionNet, int months) {
        return summary(zones, total, ratio, profitablePercentage, directions, directionNet, months, 10);
    }

    private static ValidationSummary summary(
            int zones, double total, double ratio, double profitablePercentage,
            List<Direction> directions, Map<Direction, Double> directionNet, int months, int tradesPerMonth) {
        List<MonthlyResult> monthly = java.util.stream.IntStream.range(0, months)
                .mapToObj(index -> new MonthlyResult(YearMonth.of(2025, index + 1), tradesPerMonth, 1.0))
                .toList();
        return new ValidationSummary("candidate", zones, monthly, total, 1.0, -1.0, 10.0,
                ratio, Map.copyOf(directionNet), Set.copyOf(directions), profitablePercentage);
    }
}
