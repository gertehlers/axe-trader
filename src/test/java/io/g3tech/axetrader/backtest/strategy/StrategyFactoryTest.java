package io.g3tech.axetrader.backtest.strategy;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

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
    void buildsLongOnlyStrategyFromIndicators() {
        BarSeries series = barSeriesFactory.build(
                backtestProperties.getEpic(),
                backtestProperties.getLimit(),
                backtestProperties.getTimeframeMinutes());
        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());

        Strategy strategy = strategyFactory.build(indicators, backtestProperties.getStrategy());

        assertThat(strategy).isNotNull();
        assertThat(strategy.getName()).isNotBlank();
        assertThatCode(() -> strategy.shouldEnter(50)).doesNotThrowAnyException();
    }
}
