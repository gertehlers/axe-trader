package io.g3tech.axetrader.backtest.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ObservationBatch;
import io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.session.SessionBoundary;
import io.g3tech.axetrader.backtest.discovery.session.TradingSessionCalendar;
import io.g3tech.axetrader.backtest.discovery.session.UnknownSessionBoundaryException;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.EntryFeatureExtractor;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion.Reason.INDICATOR_WARMUP;
import static io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion.Reason.NO_EXECUTABLE_NEXT_BAR;
import static io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion.Reason.NON_FINITE_FEATURE;
import static io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion.Reason.UNKNOWN_SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservableStateExtractorTest {

    private static final int SIGNAL_INDEX = 225;

    @Test
    void emitsBothDirectionsWithRichOrderedFiniteFeatures() {
        Fixture fixture = fixture(false, false);

        ObservationBatch batch = extract(fixture, knownCalendar(), SIGNAL_INDEX);

        assertThat(batch.exclusions()).isEmpty();
        assertThat(batch.states()).hasSize(2);
        assertThat(batch.states()).extracting(state -> state.id().direction())
                .containsExactly(Direction.LONG, Direction.SHORT);
        assertThat(batch.states()).allSatisfy(state -> {
            assertThat(state.signalIndex()).isEqualTo(SIGNAL_INDEX);
            assertThat(state.entryIndex()).isEqualTo(SIGNAL_INDEX + 1);
            assertThat(state.id().signalTime()).isEqualTo(fixture.market().mid().getBar(SIGNAL_INDEX).getEndTime());
            assertThat(state.minutesToTradingClose()).isEqualTo(75);
            assertThat(state.features().values()).containsKeys(
                    "rsi", "rsi.delta.1", "rsi.delta.2", "rsi.delta.3",
                    "rsi.delta.6", "rsi.delta.12",
                    "pillar.rsi_bb.active", "pillar.rsi_bb.persistence.3",
                    "pillar.volume_trend.activated", "minutes_to_trading_close");
            assertThat(state.features().values().values()).allMatch(Double::isFinite);
            assertThat(state.features().values().keySet())
                    .containsExactlyElementsOf(state.features().values().keySet().stream().sorted().toList());
        });
    }

    @Test
    void changingEveryFutureBarCannotChangeObservableState() {
        Fixture original = fixture(false, false);
        Fixture futureMutated = fixture(true, false);

        ObservationBatch before = extract(original, knownCalendar(), SIGNAL_INDEX);
        ObservationBatch after = extract(futureMutated, knownCalendar(), SIGNAL_INDEX);

        assertThat(after.exclusions()).isEmpty();
        assertThat(after.states()).hasSameSizeAs(before.states());
        for (int i = 0; i < before.states().size(); i++) {
            ObservableState beforeState = before.states().get(i);
            ObservableState afterState = after.states().get(i);
            assertThat(afterState.features()).isEqualTo(beforeState.features());
            assertThat(afterState.id()).isEqualTo(beforeState.id());
            assertThat(afterState).isEqualTo(beforeState);
        }
    }

    @Test
    void featureVectorIsImmutableFiniteAndHasDeterministicJacksonJson() throws Exception {
        Map<String, Double> mutable = new LinkedHashMap<>();
        mutable.put("zeta", 2.0);
        mutable.put("alpha", 1.0);
        FeatureVector vector = new FeatureVector(mutable);
        mutable.put("after", 3.0);

        assertThat(vector.values()).containsExactly(
                Map.entry("alpha", 1.0),
                Map.entry("zeta", 2.0));
        assertThat(vector.required("alpha")).isEqualTo(1.0);
        assertThatThrownBy(() -> vector.values().put("later", 4.0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ObjectMapper().writeValueAsString(vector))
                .isEqualTo("{\"alpha\":1.0,\"zeta\":2.0}");
        assertThatThrownBy(() -> new FeatureVector(Map.of("bad", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureVector(Map.of("bad", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsBothDirectionExclusionsDuringIndicatorWarmup() {
        Fixture fixture = fixture(false, false);

        ObservationBatch batch = extract(fixture, knownCalendar(), 10);

        assertExcludesBothDirections(batch, INDICATOR_WARMUP);
    }

    @Test
    void countsBothDirectionExclusionsWhenSessionBoundaryIsUnknown() {
        Fixture fixture = fixture(false, false);

        ObservationBatch batch = extract(fixture, unknownCalendar(), SIGNAL_INDEX);

        assertExcludesBothDirections(batch, UNKNOWN_SESSION);
    }

    @Test
    void countsBothDirectionExclusionsWhenThereIsNoNextBarToExecute() {
        Fixture fixture = fixture(false, false);

        ObservationBatch batch = extract(fixture, knownCalendar(), 239);

        assertExcludesBothDirections(batch, NO_EXECUTABLE_NEXT_BAR);
    }

    @Test
    void countsBothDirectionExclusionsWhenAFeatureIsNotFinite() {
        Fixture fixture = fixture(false, true);

        ObservationBatch batch = extract(fixture, knownCalendar(), SIGNAL_INDEX);

        assertExcludesBothDirections(batch, NON_FINITE_FEATURE);
    }

    private static void assertExcludesBothDirections(
            ObservationBatch batch, ObservationExclusion.Reason reason) {
        assertThat(batch.states()).isEmpty();
        assertThat(batch.exclusions()).hasSize(2);
        assertThat(batch.exclusions()).extracting(ObservationExclusion::reason)
                .containsOnly(reason);
        assertThat(batch.exclusions()).extracting(exclusion -> exclusion.id().direction())
                .containsExactly(Direction.LONG, Direction.SHORT);
    }

    private static ObservationBatch extract(Fixture fixture, TradingSessionCalendar calendar, int signalIndex) {
        return new ObservableStateExtractor(new EntryFeatureExtractor()).extract(
                "US500", 5, fixture.market(), fixture.indicators(), fixture.strategies(),
                fixture.config(), calendar, signalIndex);
    }

    private static Fixture fixture(boolean mutateFuture, boolean constantPrices) {
        BacktestProperties.Strategy config = config();
        MarketSeries market = market(mutateFuture, constantPrices);
        IndicatorBundle indicators = IndicatorBundle.from(market.mid(), config);
        ConfluenceStrategies strategies = new StrategyFactory().build(indicators, config);
        return new Fixture(market, indicators, strategies, config);
    }

    private static MarketSeries market(boolean mutateFuture, boolean constantPrices) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2026-01-05T08:00:00Z");
        for (int i = 0; i < 240; i++) {
            double close = constantPrices
                    ? 100.0
                    : 100.0 + i * 0.05 + Math.sin(i * 0.37) * 2.0;
            double volume = constantPrices ? 0.0 : 100.0 + (i % 17) * 7.0;
            if (mutateFuture && i > SIGNAL_INDEX) {
                close = 1_000_000.0 + i * 10_000.0;
                volume = 10_000_000.0 + i;
            }
            addBar(mid, start, i, close, volume);
            addBar(bid, start, i, close - 0.25, volume);
            addBar(ask, start, i, close + 0.25, volume);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static void addBar(
            BarSeries series, Instant start, int index, double close, double volume) {
        double range = Math.max(0.5, Math.abs(close) * 0.001);
        series.barBuilder()
                .timePeriod(Duration.ofMinutes(5))
                .endTime(start.plus(Duration.ofMinutes(5L * (index + 1))))
                .openPrice(close - range * 0.2)
                .highPrice(close + range)
                .lowPrice(close - range)
                .closePrice(close)
                .volume(volume)
                .add();
    }

    private static BacktestProperties.Strategy config() {
        BacktestProperties.Strategy config = new BacktestProperties.Strategy();
        config.setRsiPeriod(7);
        config.setRsiSmoothPeriod(7);
        config.setBbPeriod(20);
        config.setBbMultiplier(2.0);
        config.setEmaPeriod(50);
        config.setAtrPeriod(14);
        config.setRsiOversold(25);
        config.setRsiOverbought(75);
        config.setStopAtrMultiple(3.0);
        config.setTargetAtrMultiple(0.75);
        config.setTrendEmaPeriod(200);
        config.setTrendEmaMaxAtr(0);
        config.setConfluenceThreshold(3);
        config.setProximityAtrMultiple(0.5);
        config.setSwingLookbackBars(10);
        config.setVolumeSmaPeriod(20);
        config.setEnableCandles(true);
        config.setEnableSupportResistance(true);
        config.setEnableStructure(true);
        config.setEnableVolumeTrend(true);
        config.setEnableLong(true);
        config.setEnableShort(true);
        return config;
    }

    private static TradingSessionCalendar knownCalendar() {
        return new TradingSessionCalendar() {
            @Override
            public Optional<SessionBoundary> boundaryAfter(Instant barTime) {
                return Optional.of(new SessionBoundary(barTime.plus(Duration.ofMinutes(75)),
                        barTime.plus(Duration.ofMinutes(80))));
            }

            @Override
            public int minutesToClose(Instant barTime) {
                return 75;
            }
        };
    }

    private static TradingSessionCalendar unknownCalendar() {
        return new TradingSessionCalendar() {
            @Override
            public Optional<SessionBoundary> boundaryAfter(Instant barTime) {
                return Optional.empty();
            }

            @Override
            public int minutesToClose(Instant barTime) {
                throw new UnknownSessionBoundaryException(barTime);
            }
        };
    }

    private record Fixture(
            MarketSeries market,
            IndicatorBundle indicators,
            ConfluenceStrategies strategies,
            BacktestProperties.Strategy config) {
    }
}
