package io.g3tech.axetrader.backtest;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.experiment.ExperimentStore;
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
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * <p>Modeling: entries fill at the next bar's close on mid prices; the "net" columns subtract one
 * average bid/ask spread per round trip (the mid-fill correction). Exits are modeled intrabar by
 * {@code BacktestRunner} — the stop/target bracket fills at the level on the first bar whose
 * high/low touches it, with a conservative stop-wins tie-break — so the old close-only optimism is
 * gone. The {@code $net/trade} and {@code $net/day} columns translate points via
 * {@code backtest.contract.value-per-point}.
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
        // Timeframe override (iteration 15): aggregate to a different bar size without editing yaml,
        // e.g. -Dsweep.tf=30. All ATR-relative configs scale automatically. Defaults to yaml (5m).
        int timeframe = Integer.getInteger("sweep.tf", backtestProperties.getTimeframeMinutes());

        long loadStart = System.currentTimeMillis();
        List<HistoricalPrice> prices =
                repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                        backtestProperties.getEpic(), from, to);
        double avgSpread = prices.stream()
                .mapToDouble(p -> p.getCloseAsk() - p.getCloseBid())
                .average()
                .orElse(0.0);
        BarSeries series = barSeriesFactory.fromPrices(
                backtestProperties.getEpic(), prices, timeframe);
        prices = null; // 1m entities no longer needed; let ~400k rows go to GC

        long tradingDays = series.getBarData().stream()
                .map(bar -> bar.getEndTime().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();

        System.out.printf("%nSweep window %s → %s: %d %dm bars, %d trading days, avg spread %.2f pts (load %.1fs)%n%n",
                from, to, series.getBarCount(), timeframe,
                tradingDays, avgSpread, (System.currentTimeMillis() - loadStart) / 1000.0);

        Map<String, BacktestProperties.Strategy> grid = buildGrid(backtestProperties.getStrategy());
        boolean persist = Boolean.getBoolean("sweep.persist");

        List<SweepResult> results = new ArrayList<>();
        try (ExperimentStore store = persist ? new ExperimentStore(ExperimentStore.DEFAULT_DB) : null) {
            for (Map.Entry<String, BacktestProperties.Strategy> entry : grid.entrySet()) {
                long runStart = System.currentTimeMillis();
                BacktestProperties.Strategy config = entry.getValue();
                IndicatorBundle indicators = IndicatorBundle.from(series, config);
                ConfluenceStrategies strategies = strategyFactory.build(indicators, config);
                List<TradeResult> trades = backtestRunner.run(series, strategies, indicators, config);
                results.add(SweepResult.of(entry.getKey(), trades, tradingDays, avgSpread));
                if (store != null) {
                    long id = store.save(entry.getKey(), config, backtestProperties.getEpic(),
                            timeframe, from, to, trades, avgSpread, tradingDays);
                    System.out.printf("  persisted experiment #%d%n", id);
                }
                System.out.printf("  ran %-34s %5d trades in %.1fs%n",
                        entry.getKey(), trades.size(), (System.currentTimeMillis() - runStart) / 1000.0);
                printBreakdown("regime", trades, t -> t.regime().name(), avgSpread);
                printBreakdown("quarter", trades,
                        t -> t.entryTime().getYear() + "Q" + ((t.entryTime().getMonthValue() - 1) / 3 + 1),
                        avgSpread);
                printBreakdown("q+dir", trades,
                        t -> t.entryTime().getYear() + "Q" + ((t.entryTime().getMonthValue() - 1) / 3 + 1)
                                + "_" + t.direction().name(),
                        avgSpread);
                printBreakdown("month", trades,
                        t -> "%d-%02d".formatted(t.entryTime().getYear(), t.entryTime().getMonthValue()),
                        avgSpread);
            }
        }

        results.sort((a, b) -> Double.compare(b.netWinRate, a.netWinRate));

        double valuePerPoint = backtestProperties.getContract().getValuePerPoint();
        System.out.printf("%n(net = after one bid/ask spread per round trip; $ at %.2f per point)%n",
                valuePerPoint);
        System.out.printf("%-34s %6s %8s %6s %9s %7s %10s %11s %10s%n",
                "config", "trades", "per-day", "win%", "netWin%", "avgR", "netAvgPnl", "$net/trade", "$net/day");
        for (SweepResult r : results) {
            System.out.printf("%-34s %6d %8.1f %5.0f%% %8.0f%% %7.2f %10.2f %11.2f %10.2f%n",
                    r.id, r.trades, r.tradesPerDay, r.winRate * 100, r.netWinRate * 100,
                    r.avgR, r.netAvgPnl,
                    r.netAvgPnl * valuePerPoint,
                    r.netAvgPnl * r.tradesPerDay * valuePerPoint);
        }
    }

    /**
     * Iteration 8 grid (see TODO.md tuning log): the pre-committed final candidate, LONG-only —
     * iteration 7 unmasked shorts as crash-only on US500. Run once on the in-sample window to
     * confirm the standalone long-only metrics (with monthly distribution), then ONCE on 2026
     * ({@code -Dsweep.from=2026-01-01T00:00:00Z -Dsweep.to=2026-05-02T00:00:00Z}) as the final
     * out-of-sample check. No retuning on the 2026 result.
     */
    private static Map<String, BacktestProperties.Strategy> buildGrid(BacktestProperties.Strategy base) {
        Map<String, BacktestProperties.Strategy> grid = new LinkedHashMap<>();

        // Iteration 17 (2026-07-04): map the MOMENTUM @ 15m response surface around the promoted
        // profile (now the yaml `base`: mode MOMENTUM, thr3, look20, slope50, stop1.5, tiers 1.5/3.0,
        // trail2.5). The 5m→15m timeframe jump + a handful of exit knobs got us the first net-positive
        // OOS config; most of the surface is unmapped. Vary one dimension at a time to see which knobs
        // the +0.10 OOS edge actually depends on. Run this whole grid across timeframes with
        // -Dsweep.tf ∈ {8,10,12,15,20,25} to also map the duration curve. IN-SAMPLE only — the 2026
        // window is partially burned for the anchor, so tune here and walk-forward, don't peek at OOS.
        grid.put("anchor_promoted", variant(base, s -> {}));

        // Entry — confluence threshold (how many of the 4 momentum votes must agree).
        grid.put("thr2", variant(base, s -> s.setConfluenceThreshold(2)));
        grid.put("thr4", variant(base, s -> s.setConfluenceThreshold(4)));

        // Entry — breakout lookback (bars the close must break above to signal).
        grid.put("look10", variant(base, s -> s.setSwingLookbackBars(10)));
        grid.put("look15", variant(base, s -> s.setSwingLookbackBars(15)));
        grid.put("look30", variant(base, s -> s.setSwingLookbackBars(30)));

        // Entry — regime slope gate (0 = off; higher = stricter up-regime requirement).
        grid.put("slope0", variant(base, s -> s.setTrendEmaSlopeLookback(0)));
        grid.put("slope25", variant(base, s -> s.setTrendEmaSlopeLookback(25)));
        grid.put("slope100", variant(base, s -> s.setTrendEmaSlopeLookback(100)));

        // Exit — initial (pre-T1) stop distance.
        grid.put("stop1.0", variant(base, s -> s.setStopAtrMultiple(1.0)));
        grid.put("stop2.0", variant(base, s -> s.setStopAtrMultiple(2.0)));

        // Exit — tier take-profit levels (where the first two thirds bank).
        grid.put("tiers1.0_2.0", variant(base, s -> {
            s.setTier1AtrMultiple(1.0);
            s.setTier2AtrMultiple(2.0);
        }));
        grid.put("tiers2.0_4.0", variant(base, s -> {
            s.setTier1AtrMultiple(2.0);
            s.setTier2AtrMultiple(4.0);
        }));

        // Exit — trailing distance for the final third (how much room winners get to run).
        grid.put("trail1.5", variant(base, s -> s.setTrailAtrMultiple(1.5)));
        grid.put("trail3.5", variant(base, s -> s.setTrailAtrMultiple(3.5)));

        return grid;
    }

    /** Win%/net-expectancy breakdown of one config's trades, grouped by the given key. */
    private static void printBreakdown(
            String label, List<TradeResult> trades, Function<TradeResult, String> keyFn, double avgSpread) {
        Map<String, List<TradeResult>> groups = trades.stream()
                .collect(Collectors.groupingBy(keyFn, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<TradeResult>> group : groups.entrySet()) {
            List<TradeResult> groupTrades = group.getValue();
            long wins = groupTrades.stream().filter(t -> t.pnl() > 0).count();
            double avgPnl = groupTrades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
            System.out.printf("      %-8s %-8s %5d trades  win %3.0f%%  netAvgPnl %+7.2f%n",
                    label, group.getKey(), groupTrades.size(),
                    100.0 * wins / groupTrades.size(), avgPnl - avgSpread);
        }
    }

    private static BacktestProperties.Strategy variant(
            BacktestProperties.Strategy base, Consumer<BacktestProperties.Strategy> mutation) {
        BacktestProperties.Strategy copy = copy(base);
        mutation.accept(copy);
        return copy;
    }

    private static BacktestProperties.Strategy copy(BacktestProperties.Strategy s) {
        BacktestProperties.Strategy c = new BacktestProperties.Strategy();
        c.setMode(s.getMode());
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
        c.setMaxHoldingBars(s.getMaxHoldingBars());
        c.setTrendEmaPeriod(s.getTrendEmaPeriod());
        c.setTrendEmaMaxAtr(s.getTrendEmaMaxAtr());
        c.setTrendEmaSlopeLookback(s.getTrendEmaSlopeLookback());
        c.setScaleOutEnabled(s.isScaleOutEnabled());
        c.setTier1AtrMultiple(s.getTier1AtrMultiple());
        c.setTier2AtrMultiple(s.getTier2AtrMultiple());
        c.setTrailAtrMultiple(s.getTrailAtrMultiple());
        c.setConfluenceThreshold(s.getConfluenceThreshold());
        c.setProximityAtrMultiple(s.getProximityAtrMultiple());
        c.setSwingLookbackBars(s.getSwingLookbackBars());
        c.setVolumeSmaPeriod(s.getVolumeSmaPeriod());
        c.setEnableCandles(s.isEnableCandles());
        c.setEnableSupportResistance(s.isEnableSupportResistance());
        c.setEnableStructure(s.isEnableStructure());
        c.setEnableVolumeTrend(s.isEnableVolumeTrend());
        c.setEnableLong(s.isEnableLong());
        c.setEnableShort(s.isEnableShort());
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
