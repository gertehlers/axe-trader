package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.store.DiscoveryStore;
import io.g3tech.axetrader.backtest.discovery.validation.ExecutableValidator;
import io.g3tech.axetrader.backtest.discovery.validation.FinalOosGate;
import io.g3tech.axetrader.backtest.discovery.validation.MonthlyResult;
import io.g3tech.axetrader.backtest.discovery.validation.PromotionGate;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationStatistics;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationSummary;
import io.g3tech.axetrader.backtest.series.MarketSeries;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TreeMap;
import java.util.Objects;

/**
 * The one-shot final-OOS application service. It can only load a persisted frozen candidate and
 * always owns the exact protected window; callers cannot supply or mutate either definition.
 */
public final class FinalValidationService {

    private final Path persistencePath;
    private final ProtectedWindowLoader loader;
    private final ExecutableValidator validator;
    private final PromotionGate promotionGate;
    private final FinalOosGate finalGate;

    public FinalValidationService(Path persistencePath, ProtectedWindowLoader loader) {
        this(persistencePath, loader, new ExecutableValidator(), new PromotionGate(), new FinalOosGate());
    }

    FinalValidationService(
            Path persistencePath,
            ProtectedWindowLoader loader,
            ExecutableValidator validator,
            PromotionGate promotionGate,
            FinalOosGate finalGate) {
        this.persistencePath = Objects.requireNonNull(persistencePath, "persistencePath");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.promotionGate = Objects.requireNonNull(promotionGate, "promotionGate");
        this.finalGate = Objects.requireNonNull(finalGate, "finalGate");
    }

    public Result run(String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        try (DiscoveryStore store = DiscoveryStore.open(persistencePath)) {
            DiscoveryStore.StoredCandidate stored = store.findFrozenCandidate(candidateId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Missing immutable frozen candidate: " + candidateId));
            PromotionGate.GateResult promotion = promotionGate.evaluate(stored.developmentSummary());
            if (!promotion.passed()) {
                throw new IllegalStateException(
                        "Candidate did not pass development promotion gate: " + promotion.failures());
            }
            if (store.windowSpent(DiscoveryWindowPolicy.OOS_FROM, DiscoveryWindowPolicy.OOS_TO)) {
                throw new IllegalStateException("Protected OOS window has already been spent");
            }
            try {
                store.appendWindowSpent(
                        stored.runId(), DiscoveryWindowPolicy.OOS_FROM, DiscoveryWindowPolicy.OOS_TO, candidateId);
            } catch (IllegalStateException exception) {
                if (store.windowSpent(DiscoveryWindowPolicy.OOS_FROM, DiscoveryWindowPolicy.OOS_TO)) {
                    throw new IllegalStateException("Protected OOS window has already been spent", exception);
                }
                throw exception;
            }

            ProtectedWindow data = loader.load(DiscoveryWindowPolicy.OOS_FROM, DiscoveryWindowPolicy.OOS_TO);
            List<io.g3tech.axetrader.backtest.discovery.validation.ValidationTrade> trades =
                    validator.run(stored.candidate(), data.market(), data.states());
            ValidationSummary calculated = trades.isEmpty()
                    ? new ValidationSummary(
                            stored.candidate().id(), stored.candidate().rule().independentZones(),
                            List.of(), 0, 0, 0, 0, 0,
                            java.util.Map.of(stored.candidate().rule().direction(), 0.0),
                            java.util.Set.of(stored.candidate().rule().direction()), Double.NaN)
                    : ValidationStatistics.summarize(stored.candidate(), trades);
            ValidationSummary summary = withProtectedMonths(calculated);
            int developmentTrades = stored.developmentSummary().monthlyResults().stream()
                    .mapToInt(result -> result.tradeCount()).sum();
            double developmentDrawdownPerTrade = developmentTrades == 0
                    ? Double.POSITIVE_INFINITY
                    : stored.developmentSummary().maximumDrawdown() / developmentTrades;
            FinalOosGate.GateResult gate = finalGate.evaluate(summary, developmentDrawdownPerTrade);

            store.appendExperimentEvent(stored.runId(), "final_oos_result",
                    java.util.Map.of("candidate_id", candidateId, "passed", gate.passed(),
                            "failures", gate.failures()));
            return new Result(candidateId, summary, gate);
        }
    }

    static ValidationSummary withProtectedMonths(ValidationSummary summary) {
        TreeMap<YearMonth, MonthlyResult> byMonth = new TreeMap<>();
        summary.monthlyResults().forEach(result -> byMonth.put(result.month(), result));
        YearMonth first = YearMonth.from(DiscoveryWindowPolicy.OOS_FROM.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(DiscoveryWindowPolicy.OOS_TO.minusNanos(1).atZone(ZoneOffset.UTC));
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            byMonth.putIfAbsent(month, new MonthlyResult(month, 0, 0));
        }
        List<MonthlyResult> months = List.copyOf(byMonth.values());
        List<Double> nets = months.stream().map(MonthlyResult::netPnl).sorted().toList();
        List<MonthlyResult> sampled = months.stream().filter(MonthlyResult::sampled).toList();
        List<Double> sampledNets = sampled.stream().map(MonthlyResult::netPnl).sorted().toList();
        int middle = sampledNets.size() / 2;
        double median = sampledNets.isEmpty() ? Double.NaN
                : sampledNets.size() % 2 == 0
                ? (sampledNets.get(middle - 1) + sampledNets.get(middle)) / 2
                : sampledNets.get(middle);
        double profitablePercentage = sampled.isEmpty() ? Double.NaN
                : sampled.stream().filter(month -> month.netPnl() > 0).count() * 100.0 / sampled.size();
        return new ValidationSummary(
                summary.candidateId(), summary.independentZones(), months, summary.totalNet(), median,
                nets.getFirst(), summary.maximumDrawdown(), summary.netToMaximumDrawdown(),
                summary.totalNetByDirection(), summary.enabledDirections(), profitablePercentage);
    }

    @FunctionalInterface
    public interface ProtectedWindowLoader {
        ProtectedWindow load(Instant from, Instant to);
    }

    public record ProtectedWindow(MarketSeries market, List<ObservableState> states) {
        public ProtectedWindow {
            Objects.requireNonNull(market, "market");
            states = List.copyOf(Objects.requireNonNull(states, "states"));
        }
    }

    public record Result(String candidateId, ValidationSummary summary, FinalOosGate.GateResult gate) {
        public Result {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(gate, "gate");
        }
    }
}
