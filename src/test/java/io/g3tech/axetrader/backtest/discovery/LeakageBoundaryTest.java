package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.session.SessionBoundary;
import io.g3tech.axetrader.backtest.discovery.session.TradingSessionCalendar;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LeakageBoundaryTest {

    @Test
    void mutatingFutureBarsAfterStateExtractionChangesOnlyTheOfflineForwardLabel() {
        ObservableState state = TestObservations.state(Direction.LONG, 0, 1, 1.0);
        ObservableState stateBeforeFutureMutation = copyOf(state);
        MarketSeries market = market();
        ForwardPathLabeller labeller = new ForwardPathLabeller();

        ForwardPathLabel originalLabel = labeller.label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market));
        mutateFutureBar(market, 2);
        ForwardPathLabel changedLabel = labeller.label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market));

        assertThat(state).isEqualTo(stateBeforeFutureMutation);
        assertThat(changedLabel).isNotEqualTo(originalLabel);
        assertThat(changedLabel.mfePoints()).isGreaterThan(originalLabel.mfePoints());
    }

    @Test
    void labellerDoesNotAcceptObservableState() {
        assertThat(Arrays.stream(ForwardPathLabeller.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .doesNotContain(ObservableState.class);
    }

    private static TradingSessionCalendar closeAt(MarketSeries market) {
        Instant finalTime = market.mid().getBar(market.mid().getEndIndex()).getEndTime();
        return new TradingSessionCalendar() {
            @Override
            public Optional<SessionBoundary> boundaryAfter(Instant barTime) {
                return Optional.of(new SessionBoundary(finalTime, finalTime.plus(Duration.ofHours(1))));
            }

            @Override
            public int minutesToClose(Instant barTime) {
                return (int) Duration.between(barTime, finalTime).toMinutes();
            }
        };
    }

    private static ObservableState copyOf(ObservableState state) {
        return new ObservableState(
                state.id(), state.signalIndex(), state.entryIndex(), state.entryAtr(),
                state.minutesToTradingClose(), new FeatureVector(state.features().values()));
    }

    private static void mutateFutureBar(MarketSeries market, int index) {
        market.mid().getBar(index).addPrice(market.mid().numFactory().numOf(200.5));
        market.bid().getBar(index).addPrice(market.bid().numFactory().numOf(200.0));
        market.ask().getBar(index).addPrice(market.ask().numFactory().numOf(201.0));
    }

    private static MarketSeries market() {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2026-01-05T00:00:00Z");
        for (int index = 0; index < 4; index++) {
            Instant end = start.plus(Duration.ofMinutes(5L * (index + 1)));
            double bidClose = 100.0;
            add(mid, end, bidClose + 0.5);
            add(bid, end, bidClose);
            add(ask, end, bidClose + 1.0);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static void add(BarSeries series, Instant end, double close) {
        series.barBuilder()
                .timePeriod(Duration.ofMinutes(5))
                .endTime(end)
                .openPrice(close)
                .highPrice(close)
                .lowPrice(close)
                .closePrice(close)
                .volume(1.0)
                .add();
    }
}
