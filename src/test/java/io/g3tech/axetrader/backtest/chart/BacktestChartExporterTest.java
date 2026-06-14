package io.g3tech.axetrader.backtest.chart;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.BacktestRunner;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BacktestChartExporterTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private StrategyFactory strategyFactory;

    @Autowired
    private BacktestRunner backtestRunner;

    @Autowired
    private BacktestChartExporter chartExporter;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void exportsConfluenceRunnerChart() throws Exception {
        BarSeries series = barSeriesFactory.build(
                backtestProperties.getEpic(),
                backtestProperties.getLimit(),
                backtestProperties.getTimeframeMinutes());
        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());

        ConfluenceStrategies strategies = strategyFactory.build(indicators, backtestProperties.getStrategy());
        List<TradeResult> trades = backtestRunner.run(series, strategies, indicators);

        long longs = trades.stream().filter(t -> t.direction() == Direction.LONG).count();
        long shorts = trades.stream().filter(t -> t.direction() == Direction.SHORT).count();
        long wins = trades.stream().filter(TradeResult::isWin).count();
        double winRate = trades.isEmpty() ? 0.0 : 100.0 * wins / trades.size();
        System.out.printf(
                "Confluence backtest: %d trades (%d long, %d short), %d wins (%.0f%%)%n",
                trades.size(), longs, shorts, wins, winRate);
        System.out.printf("Approx trading days in dataset: %.1f  →  avg %.1f trades/day%n%n",
                series.getBarCount() / 78.0, trades.size() / (series.getBarCount() / 78.0));

        // Pillar-combo breakdown — shows which pairs/triples are actually driving entries.
        Map<String, Long> comboCount = trades.stream()
                .collect(Collectors.groupingBy(
                        t -> String.join(" + ", t.reasons().stream().sorted().toList()),
                        TreeMap::new, Collectors.counting()));
        Map<String, Long> comboWins = trades.stream().filter(TradeResult::isWin)
                .collect(Collectors.groupingBy(
                        t -> String.join(" + ", t.reasons().stream().sorted().toList()),
                        Collectors.counting()));
        System.out.println("Pillar combination breakdown (count | wins | win%):");
        comboCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> {
                    long total = e.getValue();
                    long w = comboWins.getOrDefault(e.getKey(), 0L);
                    System.out.printf("  %-45s  %3d  |  %3d  |  %.0f%%%n",
                            e.getKey(), total, w, 100.0 * w / total);
                });
        System.out.println();

        // Each entry must have recorded at least `threshold` agreeing pillars; fewer would mean
        // the reason re-evaluation disagrees with the live score (e.g. a look-ahead indicator).
        int threshold = backtestProperties.getStrategy().getConfluenceThreshold();
        assertThat(trades).allSatisfy(t ->
                assertThat(t.reasons()).hasSizeGreaterThanOrEqualTo(threshold));

        chartExporter.export(series, indicators, trades, "output/charts", "runner-results");

        Path chart = Path.of("output/charts/runner-results.html");
        assertThat(chart).exists();
        String html = Files.readString(chart);
        // Chart scaffolding is always rendered; trade-specific markup depends on the data.
        assertThat(html).contains("const tradeMarkers");
        assertThat(html).contains("const longEntryData");
        assertThat(html).contains("const shortEntryData");
        assertThat(html).contains("const stopGainData");
        assertThat(html).contains("const stopLossData");
        assertThat(html).contains("const operationLabels");
        assertThat(html).contains("const tradeVisibleRange");
    }
}
