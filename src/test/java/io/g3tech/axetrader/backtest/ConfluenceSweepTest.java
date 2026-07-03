package io.g3tech.axetrader.backtest;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.BacktestRunner;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parameter-sweep harness for the 5-pillar confluence strategy. Loads the bar series once,
 * then evaluates a grid of configs in a single JVM — the per-config cost is indicator
 * computation only, instead of a full Maven/Spring/JVM start per config.
 *
 * <p>Skipped during normal {@code mvnw test} runs; enable explicitly with:
 * <pre>
 *   ./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true \
 *       [-Dsweep.from=2024-12-04T00:00:00Z] [-Dsweep.to=2026-01-01T00:00:00Z]
 * </pre>
 *
 * <p>Default window is the IN-SAMPLE period (Dec 2024 → Dec 2025). The Jan–May 2026 tail is
 * reserved for out-of-sample validation of tuned candidates — do not tune against it.
 *
 * <p>Known modeling gaps (both flatter results live): fills are on mid prices, and the ATR
 * stop/target rules trigger on bar <em>close</em>, not intrabar wicks. The "net" columns
 * subtract the average bid/ask spread per round trip; the wick gap is not yet modeled.
 */
@SpringBootTest
class ConfluenceSweepTest {

    /** In-sample tuning window. Jan 2026+ is out-of-sample — reserved for validation. */
    private static final Instant IN_SAMPLE_FROM = Instant.parse("2024-12-04T00:00:00Z");
    private static final Instant IN_SAMPLE_TO = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private HistoricalPriceRepository repository;

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private StrategyFactory strategyFactory;

    @Autowired
    private BacktestRunner backtestRunner;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void sweep() {
        Assumptions.assumeTrue(Boolean.getBoolean("sweep"),
                "sweep disabled — run with -Dsweep=true -Dtest=ConfluenceSweepTest");

        Instant from = Instant.parse(System.getProperty("sweep.from", IN_SAMPLE_FROM.toString()));
        Instant to = Instant.parse(System.getProperty("sweep.to", IN_SAMPLE_TO.toString()));

        long loadStart = System.currentTimeMillis();
        List<HistoricalPrice> prices =
                repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                        backtestProperties.getEpic(), from, to);
        double avgSpread = prices.stream()
                .mapToDouble(p -> p.getCloseAsk() - p.getCloseBid())
                .average()
                .orElse(0.0);
        BarSeries series = barSeriesFactory.fromPrices(
                backtestProperties.getEpic(), prices, backtestProperties.getTimeframeMinutes());
        prices = null; // 1m entities no longer needed; let ~400k rows go to GC

        long tradingDays = series.getBarData().stream()
                .map(bar -> bar.getEndTime().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();

        System.out.printf("%nSweep window %s → %s: %d %dm bars, %d trading days, avg spread %.2f pts (load %.1fs)%n%n",
                from, to, series.getBarCount(), backtestProperties.getTimeframeMinutes(),
                tradingDays, avgSpread, (System.currentTimeMillis() - loadStart) / 1000.0);

        Map<String, BacktestProperties.Strategy> grid = buildGrid(backtestProperties.getStrategy());

        List<SweepResult> results = new ArrayList<>();
        for (Map.Entry<String, BacktestProperties.Strategy> entry : grid.entrySet()) {
            long runStart = System.currentTimeMillis();
            BacktestProperties.Strategy config = entry.getValue();
            IndicatorBundle indicators = IndicatorBundle.from(series, config);
            ConfluenceStrategies strategies = strategyFactory.build(indicators, config);
            List<TradeResult> trades = backtestRunner.run(series, strategies, indicators);
            results.add(SweepResult.of(entry.getKey(), trades, tradingDays, avgSpread));
            System.out.printf("  ran %-34s %5d trades in %.1fs%n",
                    entry.getKey(), trades.size(), (System.currentTimeMillis() - runStart) / 1000.0);
        }

        results.sort((a, b) -> Double.compare(b.netWinRate, a.netWinRate));

        System.out.printf("%n%-34s %6s %8s %6s %9s %7s %9s %10s%n",
                "config", "trades", "per-day", "win%", "netWin%", "avgR", "avgPnl", "netAvgPnl");
        for (SweepResult r : results) {
            System.out.printf("%-34s %6d %8.1f %5.0f%% %8.0f%% %7.2f %9.2f %10.2f%n",
                    r.id, r.trades, r.tradesPerDay, r.winRate * 100, r.netWinRate * 100,
                    r.avgR, r.avgPnl, r.netAvgPnl);
        }
    }

    /**
     * Iteration 2 grid (see TODO.md tuning log for iteration 1's results and grid). All configs
     * are threshold 3 + structure off — iteration 1 showed that zone is the only one both
     * selective and profitable after spread (th2 loses money at every geometry; th4 is dead).
     * Axis A pushes stop/target geometry toward higher win rates (watching that net expectancy
     * stays positive). Axis B loosens individual pillar gates (RSI band, S/R proximity, swing
     * lookback) at fixed geometry to raise cadence toward ~5 trades/day.
     */
    private static Map<String, BacktestProperties.Strategy> buildGrid(BacktestProperties.Strategy base) {
        Map<String, BacktestProperties.Strategy> grid = new LinkedHashMap<>();

        BacktestProperties.Strategy anchor = variant(base, s -> {
            s.setConfluenceThreshold(3);
            s.setEnableStructure(false);
        });

        // Axis A: geometry push around iteration 1's winner (stop 3.0 / target 1.0 → 70% win).
        for (double stop : new double[] {3.0, 3.5, 4.0}) {
            for (double target : new double[] {0.5, 0.75, 1.0}) {
                grid.put("geo_stop%.1f_tgt%.2f".formatted(stop, target),
                        variant(anchor, s -> {
                            s.setStopAtrMultiple(stop);
                            s.setTargetAtrMultiple(target);
                        }));
            }
        }

        // Axis B: gate loosening at fixed geometry (stop 3.0 / target 1.0) to raise trade count.
        double[][] rsiBands = {{25, 75}, {30, 70}, {35, 65}};
        for (double[] rsiBand : rsiBands) {
            for (double proximity : new double[] {0.3, 0.5}) {
                for (int lookback : new int[] {10, 20}) {
                    double oversold = rsiBand[0];
                    double overbought = rsiBand[1];
                    grid.put("gates_rsi%.0f-%.0f_prox%.1f_look%d".formatted(
                                    oversold, overbought, proximity, lookback),
                            variant(anchor, s -> {
                                s.setStopAtrMultiple(3.0);
                                s.setTargetAtrMultiple(1.0);
                                s.setRsiOversold(oversold);
                                s.setRsiOverbought(overbought);
                                s.setProximityAtrMultiple(proximity);
                                s.setSwingLookbackBars(lookback);
                            }));
                }
            }
        }

        return grid;
    }

    private static BacktestProperties.Strategy variant(
            BacktestProperties.Strategy base, Consumer<BacktestProperties.Strategy> mutation) {
        BacktestProperties.Strategy copy = copy(base);
        mutation.accept(copy);
        return copy;
    }

    private static BacktestProperties.Strategy copy(BacktestProperties.Strategy s) {
        BacktestProperties.Strategy c = new BacktestProperties.Strategy();
        c.setRsiPeriod(s.getRsiPeriod());
        c.setRsiSmoothPeriod(s.getRsiSmoothPeriod());
        c.setBbPeriod(s.getBbPeriod());
        c.setBbMultiplier(s.getBbMultiplier());
        c.setEmaPeriod(s.getEmaPeriod());
        c.setAtrPeriod(s.getAtrPeriod());
        c.setRsiOversold(s.getRsiOversold());
        c.setRsiOverbought(s.getRsiOverbought());
        c.setStopAtrMultiple(s.getStopAtrMultiple());
        c.setTargetAtrMultiple(s.getTargetAtrMultiple());
        c.setConfluenceThreshold(s.getConfluenceThreshold());
        c.setProximityAtrMultiple(s.getProximityAtrMultiple());
        c.setSwingLookbackBars(s.getSwingLookbackBars());
        c.setVolumeSmaPeriod(s.getVolumeSmaPeriod());
        c.setEnableCandles(s.isEnableCandles());
        c.setEnableSupportResistance(s.isEnableSupportResistance());
        c.setEnableStructure(s.isEnableStructure());
        c.setEnableVolumeTrend(s.isEnableVolumeTrend());
        return c;
    }

    private record SweepResult(
            String id,
            int trades,
            double tradesPerDay,
            double winRate,
            double netWinRate,
            double avgR,
            double avgPnl,
            double netAvgPnl) {

        /** "Net" = after subtracting one full bid/ask spread per round trip from each trade's pnl. */
        static SweepResult of(String id, List<TradeResult> trades, long tradingDays, double avgSpread) {
            int count = trades.size();
            if (count == 0) {
                return new SweepResult(id, 0, 0, 0, 0, 0, 0, 0);
            }
            long wins = trades.stream().filter(t -> t.pnl() > 0).count();
            long netWins = trades.stream().filter(t -> t.pnl() - avgSpread > 0).count();
            double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
            double avgPnl = trades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
            return new SweepResult(id, count,
                    tradingDays == 0 ? 0 : (double) count / tradingDays,
                    (double) wins / count,
                    (double) netWins / count,
                    avgR, avgPnl, avgPnl - avgSpread);
        }
    }
}
