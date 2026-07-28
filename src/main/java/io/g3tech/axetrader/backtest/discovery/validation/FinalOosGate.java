package io.g3tech.axetrader.backtest.discovery.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The final, intentionally stricter, one-shot OOS result gate. */
public final class FinalOosGate {

    public GateResult evaluate(ValidationSummary oos, double developmentDrawdownPerTrade) {
        Objects.requireNonNull(oos, "oos");
        if (!Double.isFinite(developmentDrawdownPerTrade) || developmentDrawdownPerTrade < 0.0) {
            throw new IllegalArgumentException("development drawdown per trade must be finite and non-negative");
        }
        List<String> failures = new ArrayList<>();
        if (!(oos.totalNet() > 0.0)) {
            failures.add("total net must be positive");
        }
        List<Double> sampledMonthlyNets = oos.sampledMonths().stream()
                .map(MonthlyResult::netPnl)
                .sorted()
                .toList();
        int middle = sampledMonthlyNets.size() / 2;
        double sampledMedian = sampledMonthlyNets.isEmpty() ? Double.NaN
                : sampledMonthlyNets.size() % 2 == 0
                ? (sampledMonthlyNets.get(middle - 1) + sampledMonthlyNets.get(middle)) / 2
                : sampledMonthlyNets.get(middle);
        if (!(sampledMedian > 0.0)) {
            failures.add("median sampled monthly net must be positive");
        }
        if (!(oos.profitableSampledMonthPercentage() >= 50.0)) {
            failures.add("at least 50% of sampled OOS months must be profitable");
        }
        double oosDrawdownPerTrade = oos.monthlyResults().stream()
                .mapToInt(MonthlyResult::tradeCount).sum() == 0 ? Double.POSITIVE_INFINITY
                : oos.maximumDrawdown() / oos.monthlyResults().stream().mapToInt(MonthlyResult::tradeCount).sum();
        if (oosDrawdownPerTrade > 1.5 * developmentDrawdownPerTrade) {
            failures.add("maximum drawdown per trade exceeds 1.5 times development");
        }
        return new GateResult(failures.isEmpty(), List.copyOf(failures));
    }

    public record GateResult(boolean passed, List<String> failures) {
        public GateResult {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }
    }
}
