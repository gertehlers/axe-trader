package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.PillarVote;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.BarSeries;
import org.ta4j.core.rules.BooleanRule;
import org.ta4j.core.rules.FixedRule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EntryFeatureExtractorTest {

    @Test
    void extractsTheExistingTradeFeatureMathAtTheCompletedSignalBar() {
        BarSeries series = handBuiltSeries();
        BacktestProperties.Strategy config = config();
        IndicatorBundle indicators = IndicatorBundle.from(series, config);

        TradeFeatures features = new EntryFeatureExtractor()
                .at(series, indicators, config, 3, 4);

        assertThat(features.rsi()).isCloseTo(100.0, within(1e-9));
        assertThat(features.atr()).isCloseTo(3.0, within(1e-9));
        assertThat(features.distToSupportAtr()).isCloseTo(4.0 / 3.0, within(1e-9));
        assertThat(features.distToResistanceAtr()).isCloseTo(0.0, within(1e-9));
        assertThat(features.volumeRatio()).isCloseTo(40.0 / 35.0, within(1e-9));
        assertThat(features.hourUtc()).isEqualTo(9);
        assertThat(features.confluenceScore()).isEqualTo(4);
    }

    @Test
    void runnerTradeFeaturesEqualTheSharedExtractorAtTheSameSignalBar() {
        BarSeries series = handBuiltSeries();
        BacktestProperties.Strategy config = config();
        IndicatorBundle indicators = IndicatorBundle.from(series, config);
        PillarVote vote = new PillarVote("manual", new FixedRule(2));
        ConfluenceStrategies strategies = new ConfluenceStrategies(
                new BaseStrategy("long", new FixedRule(2), new FixedRule(5)),
                new BaseStrategy("short", BooleanRule.FALSE, BooleanRule.FALSE),
                List.of(vote),
                List.of());
        BacktestRunner runner = new BacktestRunner(new EntryFeatureExtractor());

        TradeResult result = runner.run(series, strategies, indicators, config).getFirst();
        int entryIndex = indexAt(series, result.entryTime().toInstant());
        TradeFeatures shared = new EntryFeatureExtractor().at(
                series, indicators, config, entryIndex - 1, result.reasons().size());

        assertThat(result.features()).isEqualTo(shared);
    }

    private static int indexAt(BarSeries series, Instant time) {
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            if (series.getBar(i).getEndTime().equals(time)) {
                return i;
            }
        }
        throw new AssertionError("No bar at " + time);
    }

    private static BarSeries handBuiltSeries() {
        BarSeries series = new BaseBarSeriesBuilder().withName("entry-features").build();
        Instant start = Instant.parse("2026-01-05T09:36:00Z");
        for (int i = 0; i < 8; i++) {
            double close = 10.0 + 2.0 * i;
            series.barBuilder()
                    .timePeriod(Duration.ofMinutes(5))
                    .endTime(start.plus(Duration.ofMinutes(5L * (i + 1))))
                    .openPrice(close - 0.5)
                    .highPrice(close + 1.0)
                    .lowPrice(close - 1.0)
                    .closePrice(close)
                    .volume(10.0 * (i + 1))
                    .add();
        }
        return series;
    }

    private static BacktestProperties.Strategy config() {
        BacktestProperties.Strategy config = new BacktestProperties.Strategy();
        config.setRsiPeriod(2);
        config.setRsiSmoothPeriod(1);
        config.setBbPeriod(2);
        config.setBbMultiplier(2.0);
        config.setEmaPeriod(2);
        config.setAtrPeriod(1);
        config.setSwingLookbackBars(3);
        config.setVolumeSmaPeriod(2);
        config.setStopAtrMultiple(3.0);
        config.setTargetAtrMultiple(0.75);
        config.setConfluenceThreshold(1);
        return config;
    }
}
