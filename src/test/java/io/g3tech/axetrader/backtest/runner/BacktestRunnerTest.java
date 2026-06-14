package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.rules.FixedRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BacktestRunnerTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private BacktestRunner backtestRunner;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void runsStrategyAndCollectsTradeResults() {
        BarSeries series = barSeriesFactory.build(
                backtestProperties.getEpic(),
                backtestProperties.getLimit(),
                backtestProperties.getTimeframeMinutes());
        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());
        Strategy strategy = new BaseStrategy(
                "runner-test",
                new FixedRule(60, 100, 140, 180),
                new FixedRule(70, 112, 151, 195));

        List<TradeResult> results = backtestRunner.run(series, strategy, indicators);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.entryTime()).isBefore(result.exitTime());
            assertThat(result.direction()).isNotNull();
            assertThat(result.entryPrice()).isPositive();
            assertThat(result.exitPrice()).isPositive();
            assertThat(result.regime()).isNotNull();
        });
        assertThat(results.stream().map(TradeResult::rMultiple).distinct().count()).isGreaterThan(1);
    }
}
