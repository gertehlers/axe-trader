package io.g3tech.axetrader.backtest.indicators;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IndicatorBundleTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void computesIndicatorsFromBarSeries() {
        BarSeries series = barSeriesFactory.build(
                backtestProperties.getEpic(),
                backtestProperties.getLimit(),
                backtestProperties.getTimeframeMinutes());

        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());

        assertThat(series.getBarCount()).isGreaterThan(50);
        assertThat(indicators.rsi.getValue(50).doubleValue()).isBetween(0.0, 100.0);

        double closePrice = indicators.closePrice.getValue(50).doubleValue();
        double ema = indicators.ema.getValue(50).doubleValue();
        assertThat(ema).isBetween(closePrice * 0.8, closePrice * 1.2);

        assertThat(indicators.bbUpper.getValue(50).doubleValue())
                .isGreaterThan(indicators.bbLower.getValue(50).doubleValue());
    }
}
