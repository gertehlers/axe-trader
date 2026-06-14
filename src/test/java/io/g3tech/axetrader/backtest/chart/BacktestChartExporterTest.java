package io.g3tech.axetrader.backtest.chart;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.BacktestRunner;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.rules.FixedRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BacktestChartExporterTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private BacktestRunner backtestRunner;

    @Autowired
    private BacktestChartExporter chartExporter;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void exportsInteractiveRunnerChart() throws Exception {
        BarSeries series = barSeriesFactory.build(
                backtestProperties.getEpic(),
                backtestProperties.getLimit(),
                backtestProperties.getTimeframeMinutes());
        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());
        Strategy strategy = new BaseStrategy(
                "runner-chart",
                new FixedRule(60, 100, 140, 180),
                new FixedRule(70, 112, 151, 195));
        List<TradeResult> trades = backtestRunner.run(series, strategy, indicators);

        chartExporter.export(series, indicators, trades, "output/charts", "runner-results");

        Path chart = Path.of("output/charts/runner-results.html");
        assertThat(chart).exists();
        String html = Files.readString(chart);
        assertThat(html).contains("const tradeMarkers");
        assertThat(html).contains("const longEntryData");
        assertThat(html).contains("const stopGainData");
        assertThat(html).contains("const stopLossData");
        assertThat(html).contains("const operationLabels");
        assertThat(html).contains("const tradeVisibleRange");
        assertThat(html).contains("trade-label");
        assertThat(html).contains("#2962ff");
        assertThat(html).contains("#8b0000");
        assertThat(html).contains("arrowUp");
        assertThat(html).contains("arrowDown");
        assertThat(html).contains("Long entry");
        assertThat(html).contains("Stop");
    }
}
