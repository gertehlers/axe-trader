package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicy;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutableValidatorTest {

    @Test
    void skipsMatchingSignalsUntilTheFinalExitBarThenUsesTheNextBarAskEntry() {
        FrozenCandidate candidate = candidate();
        MarketSeries market = market(25);
        List<ObservableState> states = List.of(state(12), state(10), state(11), state(21));

        List<ValidationTrade> trades = new ExecutableValidator().run(candidate, market, states);

        assertThat(trades).extracting(ValidationTrade::entryIndex).containsExactly(11, 22);
        assertThat(trades.getFirst().exitIndex()).isEqualTo(20);
        assertThat(trades.getFirst().entryPrice()).isEqualTo(101.0);
        assertThat(trades.getFirst().netPnl()).isEqualTo(1.0);
    }

    private static FrozenCandidate candidate() {
        CandidateRule rule = CandidateRule.create(Direction.LONG,
                List.of(new RuleClause("match", RuleClause.Operator.GT, 0)), 30, 1, 1);
        ExitPolicy policy = new ExitPolicy(rule, List.of(new ExitPolicy.Tier(1, 1)),
                new ExitPolicy.Stop(ExitPolicy.StopSource.STRUCTURAL, 5), Ratchet.NONE, List.of(), 48);
        return new FrozenCandidate("candidate-1", rule, policy,
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"), "features-v1", "score-v1");
    }

    private static ObservableState state(int signalIndex) {
        return new ObservableState(new ObservationId("US500", 5,
                Instant.parse("2025-06-01T00:00:00Z").plus(Duration.ofMinutes(5L * signalIndex)), Direction.LONG),
                signalIndex, signalIndex + 1, 1, 240, new FeatureVector(Map.of("match", 1.0)));
    }

    private static MarketSeries market(int count) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2025-06-01T00:00:00Z");
        for (int index = 0; index < count; index++) {
            Instant end = start.plus(Duration.ofMinutes(5L * (index + 1)));
            add(mid, end, 100.5, index == 20 ? 101.5 : 100.5, 100.5);
            add(bid, end, 100, index == 20 ? 102 : 100, 100);
            add(ask, end, 101, index == 20 ? 103 : 101, 101);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static void add(BarSeries series, Instant end, double close, double high, double low) {
        series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(end)
                .openPrice(close).highPrice(high).lowPrice(low).closePrice(close).volume(1).add();
    }
}
