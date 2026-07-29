package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic statistics for executable, chronologically ordered trade results. */
public final class ValidationStatistics {

    private ValidationStatistics() {
    }

    public static double totalNet(List<ValidationTrade> trades) {
        return ordered(trades).stream().mapToDouble(ValidationTrade::netPnl).sum();
    }

    public static Map<YearMonth, Double> netByMonth(List<ValidationTrade> trades) {
        Map<YearMonth, Double> totals = new TreeMap<>();
        for (ValidationTrade trade : ordered(trades)) {
            totals.merge(YearMonth.from(trade.entryTime().atZone(ZoneOffset.UTC)), trade.netPnl(), Double::sum);
        }
        return Collections.unmodifiableMap(totals);
    }

    public static List<MonthlyResult> monthlyResults(List<ValidationTrade> trades) {
        Map<YearMonth, Double> nets = netByMonth(trades);
        Map<YearMonth, Long> counts = new TreeMap<>();
        for (ValidationTrade trade : ordered(trades)) {
            counts.merge(YearMonth.from(trade.entryTime().atZone(ZoneOffset.UTC)), 1L, Long::sum);
        }
        return nets.entrySet().stream()
                .map(entry -> new MonthlyResult(entry.getKey(), Math.toIntExact(counts.get(entry.getKey())), entry.getValue()))
                .toList();
    }

    public static double profitableSampledMonthPercentage(List<ValidationTrade> trades) {
        List<MonthlyResult> sampled = monthlyResults(trades).stream().filter(MonthlyResult::sampled).toList();
        if (sampled.isEmpty()) {
            return Double.NaN;
        }
        long profitable = sampled.stream().filter(result -> result.netPnl() > 0.0).count();
        return profitable * 100.0 / sampled.size();
    }

    public static double medianMonthlyNet(List<ValidationTrade> trades) {
        List<Double> values = monthlyResults(trades).stream().map(MonthlyResult::netPnl).sorted().toList();
        if (values.isEmpty()) {
            return Double.NaN;
        }
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2.0 : values.get(middle);
    }

    public static double worstMonthlyNet(List<ValidationTrade> trades) {
        return monthlyResults(trades).stream().mapToDouble(MonthlyResult::netPnl).min().orElse(Double.NaN);
    }

    public static double maximumDrawdown(List<ValidationTrade> trades) {
        double balance = 0.0;
        double peak = 0.0;
        double maximum = 0.0;
        for (ValidationTrade trade : ordered(trades)) {
            balance += trade.netPnl();
            peak = Math.max(peak, balance);
            maximum = Math.max(maximum, peak - balance);
        }
        return maximum;
    }

    public static double netToMaximumDrawdown(List<ValidationTrade> trades) {
        if (trades.isEmpty()) {
            return Double.NaN;
        }
        double total = totalNet(trades);
        double drawdown = maximumDrawdown(trades);
        if (drawdown == 0.0) {
            return total > 0.0 ? Double.POSITIVE_INFINITY : Double.NaN;
        }
        return total / drawdown;
    }

    public static ValidationSummary summarize(FrozenCandidate candidate, List<ValidationTrade> trades) {
        List<ValidationTrade> ordered = ordered(trades);
        double total = totalNet(ordered);
        return new ValidationSummary(candidate.id(), candidate.rule().independentZones(), monthlyResults(ordered), total,
                medianMonthlyNet(ordered), worstMonthlyNet(ordered), maximumDrawdown(ordered), netToMaximumDrawdown(ordered),
                Map.of(candidate.rule().direction(), total), java.util.Set.of(candidate.rule().direction()),
                profitableSampledMonthPercentage(ordered));
    }

    public static Map<Direction, Double> netByDirection(List<ValidationTrade> trades) {
        Map<Direction, Double> totals = new EnumMap<>(Direction.class);
        for (ValidationTrade trade : ordered(trades)) {
            totals.merge(trade.direction(), trade.netPnl(), Double::sum);
        }
        return Map.copyOf(totals);
    }

    private static List<ValidationTrade> ordered(List<ValidationTrade> trades) {
        return List.copyOf(trades).stream().sorted(Comparator.comparing(ValidationTrade::entryTime)
                .thenComparingInt(ValidationTrade::entryIndex)).toList();
    }
}
