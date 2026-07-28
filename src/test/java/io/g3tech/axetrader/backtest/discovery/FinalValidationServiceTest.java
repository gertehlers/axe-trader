package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicy;
import io.g3tech.axetrader.backtest.discovery.store.DiscoveryRun;
import io.g3tech.axetrader.backtest.discovery.store.DiscoveryStore;
import io.g3tech.axetrader.backtest.discovery.validation.FrozenCandidate;
import io.g3tech.axetrader.backtest.discovery.validation.MonthlyResult;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationSummary;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationTrade;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalValidationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsMissingMutableAndPromotionFailingCandidatesBeforeLoadingHistory() {
        Path missing = temporaryDirectory.resolve("missing.sqlite");
        AtomicInteger reads = new AtomicInteger();
        FinalValidationService.ProtectedWindowLoader loader = (from, to) -> {
            reads.incrementAndGet();
            return new FinalValidationService.ProtectedWindow(market(), List.of());
        };

        assertThatThrownBy(() -> new FinalValidationService(missing, loader).run("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable frozen");

        Path mutable = temporaryDirectory.resolve("mutable.sqlite");
        persist(mutable, false, true);
        assertThatThrownBy(() -> new FinalValidationService(mutable, loader).run(candidate().id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable frozen");

        Path failing = temporaryDirectory.resolve("failing.sqlite");
        persist(failing, true, false);
        assertThatThrownBy(() -> new FinalValidationService(failing, loader).run(candidate().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("promotion gate");
        assertThat(reads).hasValue(0);
    }

    @Test
    void readsExactlyTheProtectedWindowOnceAndSpendsItEvenWhenFinalGateFails() {
        Path database = temporaryDirectory.resolve("passing.sqlite");
        persist(database, true, true);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger competingReads = new AtomicInteger();
        FinalValidationService competitor = new FinalValidationService(database, (from, to) -> {
            competingReads.incrementAndGet();
            return new FinalValidationService.ProtectedWindow(market(), List.of());
        });
        FinalValidationService service = new FinalValidationService(database, (from, to) -> {
            assertThat(from).isEqualTo(DiscoveryWindowPolicy.OOS_FROM);
            assertThat(to).isEqualTo(DiscoveryWindowPolicy.OOS_TO);
            assertThatThrownBy(() -> competitor.run(candidate().id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been spent");
            assertThat(competingReads).hasValue(0);
            reads.incrementAndGet();
            return new FinalValidationService.ProtectedWindow(market(), List.of());
        });

        FinalValidationService.Result result = service.run(candidate().id());
        assertThat(result.gate().passed()).isFalse();
        assertThat(result.summary().monthlyResults())
                .hasSize(5)
                .allSatisfy(month -> {
                    assertThat(month.tradeCount()).isZero();
                    assertThat(month.sampled()).isFalse();
                });
        assertThatThrownBy(() -> service.run(candidate().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been spent");
        assertThat(reads).hasValue(1);
    }

    @Test
    void calculatesFinalMedianAndProfitabilityFromSampledMonthsOnly() {
        ValidationSummary calculated = new ValidationSummary(
                candidate().id(),
                30,
                List.of(
                        new MonthlyResult(YearMonth.of(2026, 1), 10, 4.0),
                        new MonthlyResult(YearMonth.of(2026, 2), 10, 8.0)),
                12.0,
                6.0,
                4.0,
                2.0,
                6.0,
                Map.of(Direction.LONG, 12.0),
                Set.of(Direction.LONG),
                100.0);

        ValidationSummary protectedSummary = FinalValidationService.withProtectedMonths(calculated);

        assertThat(protectedSummary.monthlyResults()).hasSize(5);
        assertThat(protectedSummary.monthlyResults().stream().filter(MonthlyResult::sampled)).hasSize(2);
        assertThat(protectedSummary.medianMonthlyNet()).isEqualTo(6.0);
        assertThat(protectedSummary.profitableSampledMonthPercentage()).isEqualTo(100.0);
    }

    private static void persist(Path database, boolean freeze, boolean passing) {
        FrozenCandidate candidate = candidate();
        try (DiscoveryStore store = DiscoveryStore.open(database)) {
            long runId = store.beginRun(run());
            long candidateId = store.registerCandidate(
                    runId, candidate.rule(), candidate.derivationFrom(), candidate.derivationTo());
            if (freeze) {
                store.saveFrozenCandidate(candidateId, candidate);
                store.saveValidation(candidateId, validationTrades(candidate, passing ? 10 : 1));
            }
        }
    }

    private static List<ValidationTrade> validationTrades(FrozenCandidate candidate, int tradesPerMonth) {
        List<ValidationTrade> trades = new ArrayList<>();
        int index = 0;
        for (int month = 1; month <= 6; month++) {
            Instant start = YearMonth.of(2025, month).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (int trade = 0; trade < tradesPerMonth; trade++) {
                Instant entry = start.plus(Duration.ofHours(trade));
                trades.add(new ValidationTrade(candidate.id(), Direction.LONG, index, index + 1,
                        entry, entry.plus(Duration.ofMinutes(5)), 100, 101, 1,
                        ExitReason.TARGET, List.of(), 1));
                index += 2;
            }
        }
        return trades;
    }

    private static FrozenCandidate candidate() {
        CandidateRule rule = CandidateRule.create(Direction.LONG,
                List.of(new RuleClause("rsi", RuleClause.Operator.LT, 50)), 30, 1, 1);
        return new FrozenCandidate("candidate", rule,
                new ExitPolicy(rule, List.of(new ExitPolicy.Tier(1, 1)),
                        new ExitPolicy.Stop(ExitPolicy.StopSource.MAE_P50, 1),
                        Ratchet.NONE, List.of(), 48),
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-04-01T00:00:00Z"),
                "features-v1", "score-v1");
    }

    private static DiscoveryRun run() {
        return new DiscoveryRun("input", "config", "features-v1", "score-v1", "commit", false,
                "US500", 5, Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static MarketSeries market() {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant end = DiscoveryWindowPolicy.OOS_FROM.plus(Duration.ofMinutes(5));
        for (BarSeries series : List.of(mid, bid, ask)) {
            series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(end)
                    .openPrice(100).highPrice(100).lowPrice(100).closePrice(100).volume(1).add();
        }
        return new MarketSeries(mid, bid, ask);
    }
}
