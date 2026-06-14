package io.g3tech.axetrader.backtest.series;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BarSeriesFactoryTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Test
    void buildsAggregatedSeriesFromHistoricalPrices() {
        BarSeries series = barSeriesFactory.build("US500", 10000, 5);

        assertThat(series).isNotNull();
        assertThat(series.getBarCount()).isGreaterThan(0);

        int expectedApprox = 10000 / 5;
        assertThat(series.getBarCount())
                .isBetween((int) (expectedApprox * 0.5), (int) (expectedApprox * 1.5));

        var first = series.getBar(series.getBeginIndex());
        var last = series.getBar(series.getEndIndex());
        assertThat(first.getEndTime()).isBefore(last.getEndTime());
    }
}
