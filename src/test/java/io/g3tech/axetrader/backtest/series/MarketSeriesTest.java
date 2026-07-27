package io.g3tech.axetrader.backtest.series;

import io.g3tech.axetrader.backtest.runner.Direction;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarketSeriesTest {

    @Test
    void usesAskForLongEntriesAndBidForShortEntries() {
        MarketSeries series = marketSeries();

        assertThat(series.entryPrice(Direction.LONG, 0)).isEqualTo(101.0);
        assertThat(series.entryPrice(Direction.SHORT, 0)).isEqualTo(99.0);
    }

    @Test
    void usesBidBarsForLongExitsAndAskBarsForShortExits() {
        MarketSeries series = marketSeries();

        assertThat(series.exitBar(Direction.LONG, 0).getClosePrice().doubleValue()).isEqualTo(99.0);
        assertThat(series.exitBar(Direction.SHORT, 0).getClosePrice().doubleValue()).isEqualTo(101.0);
    }

    private static MarketSeries marketSeries() {
        return new MarketSeries(series("mid", 100.0), series("bid", 99.0), series("ask", 101.0));
    }

    private static BarSeries series(String name, double close) {
        BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
        series.barBuilder()
                .timePeriod(Duration.ofMinutes(5))
                .endTime(Instant.parse("2026-01-01T00:05:00Z"))
                .openPrice(close)
                .highPrice(close)
                .lowPrice(close)
                .closePrice(close)
                .volume(1)
                .add();
        return series;
    }
}
