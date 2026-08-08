package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
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

class ForwardPathLabellerTest {

    @Test
    void labelsLongUsingAskEntryAndBidExitPath() {
        MarketSeries market = market(
                new double[] {99, 100, 103, 102, 103},
                new double[] {99, 100, 104, 102, 103},
                new double[] {99, 100, 103, 100, 103},
                new double[] {101, 101, 105, 104, 105},
                new double[] {101, 101, 105, 104, 105});

        ObservableState state = state(Direction.LONG, 0, 1, 1.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 4));

        assertThat(label.status()).isEqualTo(LabelStatus.TRADING_CLOSE);
        assertThat(label.entryPrice()).isEqualTo(101.0);
        assertThat(label.exitPath()).hasSize(3);
        assertThat(label.mfePoints()).isEqualTo(3.0);
        assertThat(label.maePoints()).isEqualTo(-1.0);
        assertThat(label.mfeAtr()).isEqualTo(3.0);
        assertThat(label.maeAtr()).isEqualTo(-1.0);
        assertThat(label.maeBeforeMfe()).isFalse();
        assertThat(label.excursionOrder()).isEqualTo(ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE);
        assertThat(label.horizonReturnsPoints()).containsEntry(5, 2.0).containsEntry(15, 2.0);
    }

    @Test
    void labelsShortUsingBidEntryAndAskExitPath() {
        MarketSeries market = market(
                new double[] {100, 99, 97, 98, 97},
                new double[] {100, 99, 98, 96, 97},
                new double[] {100, 99, 97, 98, 97},
                new double[] {102, 101, 96, 98, 97},
                new double[] {102, 101, 99, 100, 100});

        ObservableState state = state(Direction.SHORT, 0, 1, 1.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 4));

        assertThat(label.status()).isEqualTo(LabelStatus.TRADING_CLOSE);
        assertThat(label.entryPrice()).isEqualTo(99.0);
        assertThat(label.mfePoints()).isEqualTo(3.0);
        assertThat(label.maePoints()).isEqualTo(-1.0);
        assertThat(label.maeBeforeMfe()).isFalse();
        assertThat(label.excursionOrder()).isEqualTo(ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE);
    }

    @Test
    void keepsExactlyFortyEightExecutableExitBars() {
        MarketSeries market = flatMarket(60);

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 59));

        assertThat(label.status()).isEqualTo(LabelStatus.COMPLETE_48_BARS);
        assertThat(label.exitPath()).hasSize(48);
        assertThat(label.exitPath().getLast().index()).isEqualTo(49);
        assertThat(label.horizonReturnsPoints()).containsKey(240);
    }

    @Test
    void stopsAtTheKnownBrokerCloseAfterTwelveBars() {
        MarketSeries market = flatMarket(20);

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 13));

        assertThat(label.status()).isEqualTo(LabelStatus.TRADING_CLOSE);
        assertThat(label.exitPath()).hasSize(12);
        assertThat(label.exitPath().getLast().index()).isEqualTo(13);
        assertThat(label.eligibleForDiscovery()).isTrue();
    }

    @Test
    void marksAnUnexplainedGapIncompleteRatherThanTreatingItAsAClose() {
        MarketSeries market = flatMarketWithGap(8, 4);

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 7));

        assertThat(label.status()).isEqualTo(LabelStatus.INCOMPLETE_GAP);
        assertThat(label.eligibleForDiscovery()).isFalse();
        assertThat(label.exitPath()).hasSize(2);
    }

    @Test
    void snapsASessionCloseThatFallsBetweenBarsToTheLastExecutableBar() {
        MarketSeries market = flatMarket(20);
        // A broker close is a one-minute timestamp such as 20:59 and so almost never lands on the
        // 5-minute grid. Looking for an exact match finds nothing and abandons the whole path.
        Instant offGrid = market.mid().getBar(13).getEndTime().minus(Duration.ofMinutes(1));

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAtInstant(offGrid));

        assertThat(label.status()).isEqualTo(LabelStatus.TRADING_CLOSE);
        assertThat(label.exitPath()).hasSize(11);
        assertThat(label.exitPath().getLast().index()).isEqualTo(12);
        assertThat(label.eligibleForDiscovery()).isTrue();
    }

    private static TradingSessionCalendar closeAtInstant(Instant finalTime) {
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

    @Test
    void marksAnUnavailableEntryBarAsNotExecutable() {
        MarketSeries market = flatMarket(1);

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 0));

        assertThat(label.status()).isEqualTo(LabelStatus.NO_EXECUTABLE_NEXT_BAR);
        assertThat(label.eligibleForDiscovery()).isFalse();
        assertThat(label.exitPath()).isEmpty();
    }

    private static ObservableState state(Direction direction, int signalIndex, int entryIndex, double atr) {
        return TestObservations.state(direction, signalIndex, entryIndex, atr);
    }

    private static TradingSessionCalendar closeAt(MarketSeries market, int finalIndex) {
        Instant finalTime = market.mid().getBar(finalIndex).getEndTime();
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

    @Test
    void labelsAFifteenMinuteSeriesRatherThanAssumingFiveMinuteBars() {
        MarketSeries market = flatMarketOfTimeframe(20, 15);

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 13));

        assertThat(label.status()).isEqualTo(LabelStatus.TRADING_CLOSE);
        assertThat(label.exitPath()).hasSize(12);
        assertThat(label.eligibleForDiscovery()).isTrue();
    }

    @Test
    void keysHorizonReturnsToTheSeriesTimeframeAndSkipsUnreachableHorizons() {
        MarketSeries market = flatMarketOfTimeframe(20, 15);

        ObservableState state = state(Direction.LONG, 0, 1, 2.0);
        ForwardPathLabel label = new ForwardPathLabeller().label(
                state.id().direction(), state.entryIndex(), state.entryAtr(), market, closeAt(market, 13));

        // A 5-minute horizon cannot be read off 15-minute bars, so it must be absent rather than
        // silently answered from the wrong bar.
        assertThat(label.horizonReturnsPoints()).doesNotContainKey(5);
        assertThat(label.horizonReturnsPoints()).containsKeys(15, 30, 60, 120);
    }

    private static MarketSeries flatMarketOfTimeframe(int count, int timeframeMinutes) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2026-01-05T00:00:00Z");
        Duration timeframe = Duration.ofMinutes(timeframeMinutes);
        for (int index = 0; index < count; index++) {
            Instant end = start.plus(timeframe.multipliedBy(index + 1L));
            addOfTimeframe(mid, end, timeframe, 100.5, 101.0, 100.0);
            addOfTimeframe(bid, end, timeframe, 100.0, 100.0, 100.0);
            addOfTimeframe(ask, end, timeframe, 101.0, 101.0, 101.0);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static void addOfTimeframe(BarSeries series, Instant end, Duration timeframe,
                                       double close, double high, double low) {
        series.barBuilder()
                .timePeriod(timeframe)
                .endTime(end)
                .openPrice(close)
                .highPrice(high)
                .lowPrice(low)
                .closePrice(close)
                .volume(1)
                .add();
    }

    private static MarketSeries flatMarket(int count) {
        double[] close = new double[count];
        double[] high = new double[count];
        double[] low = new double[count];
        double[] askClose = new double[count];
        double[] askHigh = new double[count];
        for (int i = 0; i < count; i++) {
            close[i] = 100.0;
            high[i] = 100.0;
            low[i] = 100.0;
            askClose[i] = 101.0;
            askHigh[i] = 101.0;
        }
        return market(close, high, low, askClose, askHigh);
    }

    private static MarketSeries flatMarketWithGap(int count, int gapBeforeIndex) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2026-01-05T00:00:00Z");
        for (int index = 0; index < count; index++) {
            Instant end = start.plus(Duration.ofMinutes(5L * (index + 1)));
            if (index >= gapBeforeIndex) {
                end = end.plus(Duration.ofMinutes(5));
            }
            add(mid, end, 100.5, 101.0, 100.0);
            add(bid, end, 100.0, 100.0, 100.0);
            add(ask, end, 101.0, 101.0, 101.0);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static MarketSeries market(
            double[] bidClose, double[] bidHigh, double[] bidLow,
            double[] askClose, double[] askHigh) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2026-01-05T00:00:00Z");
        for (int index = 0; index < bidClose.length; index++) {
            Instant end = start.plus(Duration.ofMinutes(5L * (index + 1)));
            add(mid, end, (bidClose[index] + askClose[index]) / 2.0,
                    (bidHigh[index] + askHigh[index]) / 2.0,
                    bidLow[index]);
            add(bid, end, bidClose[index], bidHigh[index], bidLow[index]);
            add(ask, end, askClose[index], askHigh[index], askClose[index]);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static void add(BarSeries series, Instant end, double close, double high, double low) {
        series.barBuilder()
                .timePeriod(Duration.ofMinutes(5))
                .endTime(end)
                .openPrice(close)
                .highPrice(high)
                .lowPrice(low)
                .closePrice(close)
                .volume(1.0)
                .add();
    }
}
