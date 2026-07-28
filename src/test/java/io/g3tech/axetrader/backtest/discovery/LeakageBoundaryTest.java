package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LeakageBoundaryTest {

    @Test
    void changingFutureBarsChangesOnlyTheOfflineForwardLabel() {
        ObservableState state = TestObservations.state(Direction.LONG, 0, 1, 1.0);
        MarketSeries original = market(100.0);
        MarketSeries changedFuture = market(200.0);
        ForwardPathLabeller labeller = new ForwardPathLabeller();

        ForwardPathLabel originalLabel = labeller.label(state, original, closeAt(original));
        ForwardPathLabel changedLabel = labeller.label(state, changedFuture, closeAt(changedFuture));

        assertThat(state.features().required("observable")).isEqualTo(1.0);
        assertThat(changedLabel).isNotEqualTo(originalLabel);
        assertThat(changedLabel.mfePoints()).isGreaterThan(originalLabel.mfePoints());
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

    private static MarketSeries market(double futureBidClose) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2026-01-05T00:00:00Z");
        for (int index = 0; index < 4; index++) {
            Instant end = start.plus(Duration.ofMinutes(5L * (index + 1)));
            double bidClose = index == 1 ? 100.0 : futureBidClose;
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
