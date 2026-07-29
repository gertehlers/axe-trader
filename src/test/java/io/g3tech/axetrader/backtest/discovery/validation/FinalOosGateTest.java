package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FinalOosGateTest {

    @Test
    void requires_positive_net_median_and_half_of_sampled_months() {
        FinalOosGate gate = new FinalOosGate();
        ValidationSummary passing = summary(20.0, 4.0, 10.0, List.of(
                new MonthlyResult(YearMonth.of(2026, 1), 10, 8.0),
                new MonthlyResult(YearMonth.of(2026, 2), 10, -1.0),
                new MonthlyResult(YearMonth.of(2026, 3), 10, 5.0),
                new MonthlyResult(YearMonth.of(2026, 4), 2, -10.0)));

        assertThat(gate.evaluate(passing, 8.0).passed()).isTrue();
        assertThat(gate.evaluate(summary(-1.0, 4.0, 10.0, passing.monthlyResults()), 8.0).passed()).isFalse();
        ValidationSummary negativeSampledMedian = summary(20.0, 100.0, 10.0, List.of(
                new MonthlyResult(YearMonth.of(2026, 1), 10, -8.0),
                new MonthlyResult(YearMonth.of(2026, 2), 10, -1.0),
                new MonthlyResult(YearMonth.of(2026, 3), 10, 5.0),
                new MonthlyResult(YearMonth.of(2026, 4), 2, 100.0)));
        assertThat(gate.evaluate(negativeSampledMedian, 8.0).passed()).isFalse();
    }

    @Test
    void rejects_drawdown_more_than_one_and_a_half_times_development_per_trade() {
        FinalOosGate gate = new FinalOosGate();
        ValidationSummary oos = summary(20.0, 4.0, 16.0, List.of(
                new MonthlyResult(YearMonth.of(2026, 1), 10, 8.0),
                new MonthlyResult(YearMonth.of(2026, 2), 10, 5.0)));

        assertThat(gate.evaluate(oos, 0.4).passed()).isFalse();
        assertThat(gate.evaluate(oos, 0.6).passed()).isTrue();
    }

    private static ValidationSummary summary(double total, double median, double drawdown, List<MonthlyResult> months) {
        return new ValidationSummary("candidate", 30, months, total, median, -2.0, drawdown, 2.0,
                Map.of(Direction.LONG, total), Set.of(Direction.LONG), 66.7);
    }
}
