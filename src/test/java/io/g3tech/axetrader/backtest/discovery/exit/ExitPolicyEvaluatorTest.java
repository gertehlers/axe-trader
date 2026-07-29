package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ExitPolicyEvaluatorTest {

    @Test
    void executableExitUsesBidPricesAndReportsCaptureAgainstTheOracle() {
        ObservableState entry = state(0, 1, 240, 1);
        ExitPolicy policy = policy(List.of(new ExitPolicy.Tier(1, 1)), List.of());
        MarketSeries market = market(new double[][] {{100, 100, 100}, {100, 100, 100}, {103, 100.5, 101.5}},
                new double[][] {{100, 100, 100}, {100, 100, 100}, {103, 100.5, 101.5}},
                new double[][] {{101, 101, 101}, {101, 101, 101}, {104, 101.5, 102.5}});

        ExitEvaluation evaluation = new ExitPolicyEvaluator().evaluate(entry, market, policy, List.of());

        assertThat(evaluation.exitPrice()).isCloseTo(102, within(1e-9));
        assertThat(evaluation.netPnl()).isCloseTo(1, within(1e-9));
        assertThat(evaluation.captureRatio()).isCloseTo(.5, within(1e-9));
        assertThat(OracleExit.best(entry, market, 48).netPnl()).isCloseTo(2, within(1e-9));
    }

    @Test
    void twoConditionInvalidationObservedOnOneBarFillsAtTheNextBarsSidedOpen() {
        ObservableState entry = state(0, 1, 240, 1);
        RuleClause trendInvalidation = new RuleClause("trend", RuleClause.Operator.LT, 0);
        RuleClause volumeInvalidation = new RuleClause("volume", RuleClause.Operator.LT, 0);
        ExitPolicy policy = policy(List.of(new ExitPolicy.Tier(1, 5)), List.of(trendInvalidation, volumeInvalidation));
        ObservableState trendOnly = state(1, 2, 240, -1, 1);
        ObservableState deterioration = state(2, 3, 240, -1, -1);
        MarketSeries market = market(new double[][] {{100, 100, 100}, {100, 99, 99.5}, {99, 98, 98.5}, {98, 97, 97.5}},
                new double[][] {{100, 100, 100}, {100, 99, 99.5}, {99, 98, 98.5}, {98, 97, 97.5}},
                new double[][] {{101, 101, 101}, {101, 100, 100.5}, {100, 99, 99.5}, {99, 98, 98.5}});

        ExitEvaluation evaluation = new ExitPolicyEvaluator().evaluate(entry, market, policy, List.of(trendOnly, deterioration));

        assertThat(evaluation.exitIndex()).isEqualTo(3);
        assertThat(evaluation.exitPrice()).isCloseTo(97.5, within(1e-9));
        assertThat(evaluation.exitReason()).isEqualTo(ExitReason.INVALIDATION);
        assertThat(evaluation.firedInvalidations()).containsExactly(trendInvalidation, volumeInvalidation);
    }

    @Test
    void tradingCloseClosesRemainingTiersAtTheSidedClose() {
        ObservableState entry = state(0, 1, 5, 1);
        ExitPolicy policy = policy(List.of(new ExitPolicy.Tier(.5, .5), new ExitPolicy.Tier(.5, 5)), List.of());
        MarketSeries market = market(new double[][] {{100, 100, 100}, {101.2, 100.8, 101}, {102, 100.5, 101.25}},
                new double[][] {{100, 100, 100}, {100.2, 99.8, 100}, {102, 100.5, 101.25}},
                new double[][] {{101, 101, 101}, {101.2, 100.8, 101}, {103, 101.5, 102.25}});

        ExitEvaluation evaluation = new ExitPolicyEvaluator().evaluate(entry, market, policy, List.of());

        assertThat(evaluation.exitReason()).isEqualTo(ExitReason.TRADING_CLOSE);
        assertThat(evaluation.tierFills()).hasSize(2);
        assertThat(evaluation.exitPrice()).isCloseTo((101.5 + 101.25) / 2, within(1e-9));
    }

    private static ExitPolicy policy(List<ExitPolicy.Tier> tiers, List<RuleClause> invalidations) {
        CandidateRule rule = CandidateRule.create(Direction.LONG,
                List.of(new RuleClause("trend", RuleClause.Operator.GT, -2)), 1, 1, 1);
        return new ExitPolicy(rule, tiers, new ExitPolicy.Stop(ExitPolicy.StopSource.STRUCTURAL, 3),
                Ratchet.NONE, invalidations, 48);
    }

    private static ObservableState state(int signalIndex, int entryIndex, int minutesToClose, double trend) {
        return state(signalIndex, entryIndex, minutesToClose, trend, 1);
    }

    private static ObservableState state(int signalIndex, int entryIndex, int minutesToClose, double trend, double volume) {
        return new ObservableState(new ObservationId("US500", 5, Instant.parse("2026-01-01T00:05:00Z"), Direction.LONG),
                signalIndex, entryIndex, 1, minutesToClose, new FeatureVector(Map.of("trend", trend, "volume", volume)));
    }

    private static MarketSeries market(double[][] mid, double[][] bid, double[][] ask) {
        return new MarketSeries(series("mid", mid), series("bid", bid), series("ask", ask));
    }

    private static BarSeries series(String name, double[][] bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < bars.length; i++) {
            double[] bar = bars[i];
            series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(start.plus(Duration.ofMinutes(5L * (i + 1))))
                    .openPrice(bar[2]).highPrice(bar[0]).lowPrice(bar[1]).closePrice(bar[2]).volume(1).add();
        }
        return series;
    }
}
