package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ValidationStatisticsTest {

    @Test
    void calculatesUtcMonthlyMetricsAndLeavesUnsampledMonthsVisible() {
        List<ValidationTrade> trades = java.util.stream.Stream.of(
                        month("2025-01-31T23:55:00Z", 10), month("2025-02-01T00:00:00Z", 5),
                        month("2025-03-01T00:00:00Z", -4), month("2025-04-01T00:00:00Z", 7),
                        month("2025-05-01T00:00:00Z", -2), month("2025-06-01T00:00:00Z", 6),
                        List.of(trade("2025-07-01T00:00:00Z", 3)))
                .flatMap(List::stream)
                .toList();

        assertThat(ValidationStatistics.totalNet(trades)).isEqualTo(25.0);
        assertThat(ValidationStatistics.netByMonth(trades)).containsExactly(
                java.util.Map.entry(YearMonth.of(2025, 1), 10.0), java.util.Map.entry(YearMonth.of(2025, 2), 5.0),
                java.util.Map.entry(YearMonth.of(2025, 3), -4.0), java.util.Map.entry(YearMonth.of(2025, 4), 7.0),
                java.util.Map.entry(YearMonth.of(2025, 5), -2.0), java.util.Map.entry(YearMonth.of(2025, 6), 6.0),
                java.util.Map.entry(YearMonth.of(2025, 7), 3.0));
        assertThat(ValidationStatistics.monthlyResults(trades)).extracting(MonthlyResult::sampled)
                .containsExactly(true, true, true, true, true, true, false);
        assertThat(ValidationStatistics.profitableSampledMonthPercentage(trades)).isCloseTo(4 * 100.0 / 6, within(1e-9));
        assertThat(ValidationStatistics.medianMonthlyNet(trades)).isEqualTo(5.0);
        assertThat(ValidationStatistics.worstMonthlyNet(trades)).isEqualTo(-4.0);
        assertThat(ValidationStatistics.maximumDrawdown(trades)).isEqualTo(4.0);
        assertThat(ValidationStatistics.netToMaximumDrawdown(trades)).isEqualTo(6.25);
    }

    @Test
    void makesPositiveNetWithNoDrawdownAnInfiniteRatioAndFailsEmptyResults() {
        List<ValidationTrade> trades = List.of(trade("2025-01-01T00:00:00Z", 1));

        assertThat(ValidationStatistics.netToMaximumDrawdown(trades)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(ValidationStatistics.netToMaximumDrawdown(List.of())).isNaN();
    }

    private static List<ValidationTrade> month(String entryTime, double net) {
        Instant entry = Instant.parse(entryTime);
        YearMonth month = YearMonth.from(entry.atZone(java.time.ZoneOffset.UTC));
        List<ValidationTrade> trades = new java.util.ArrayList<>();
        for (int index = 0; index < 9; index++) {
            trades.add(trade(month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC)
                    .plusMinutes(index * 5L).toInstant().toString(), 0.0));
        }
        trades.add(trade(entryTime, net));
        return trades;
    }

    private static ValidationTrade trade(String entryTime, double net) {
        Instant entry = Instant.parse(entryTime);
        return new ValidationTrade("candidate", Direction.LONG, 1, 2, entry, entry.plusSeconds(300),
                101, 101 + net, net, ExitReason.TIME, List.of(), 0.5);
    }
}
