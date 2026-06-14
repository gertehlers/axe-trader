package io.g3tech.axetrader.backtest.strategy;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
class StrategyFactoryTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private StrategyFactory strategyFactory;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void buildsLongAndShortConfluenceStrategies() {
        BarSeries series = barSeriesFactory.build(
                backtestProperties.getEpic(),
                backtestProperties.getLimit(),
                backtestProperties.getTimeframeMinutes());
        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());

        ConfluenceStrategies strategies = strategyFactory.build(indicators, backtestProperties.getStrategy());

        assertThat(strategies.longStrategy()).isNotNull();
        assertThat(strategies.shortStrategy()).isNotNull();
        assertThat(strategies.longStrategy().getName()).isNotBlank();
        assertThat(strategies.shortStrategy().getName()).isNotBlank();
        assertThatCode(() -> strategies.longStrategy().shouldEnter(50)).doesNotThrowAnyException();
        assertThatCode(() -> strategies.shortStrategy().shouldEnter(50)).doesNotThrowAnyException();
    }
}
