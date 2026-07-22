package io.g3tech.axetrader.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.experiment.DashboardExporter;
import io.g3tech.axetrader.backtest.experiment.ExperimentStore;
import io.g3tech.axetrader.backtest.experiment.TradeStatistics;
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

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
    void sweep() throws Exception {
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
                            backtestProperties.getTimeframeMinutes(), from, to, trades, avgSpread, tradingDays);
                    System.out.printf("  persisted experiment #%d%n", id);
                }
                if (Boolean.getBoolean("sweep.exportDashboard")) {
                    String configJson = new ObjectMapper().writeValueAsString(config);
                    String runId = backtestProperties.getEpic() + "-" + System.currentTimeMillis();
                    Path out = Path.of("dashboard/run.json");
                    DashboardExporter.export(out, entry.getKey(), runId, config, configJson,
                            backtestProperties.getEpic(), backtestProperties.getTimeframeMinutes(),
                            from, to, backtestProperties.getContract().getValuePerPoint(),
                            avgSpread, tradingDays, series, trades);
                    System.out.println("  wrote dashboard export -> " + out);
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
        // maxDD / posQ / MAR are the pre-registered success-gate columns (see the tiered-scale-out
        // spec): consistency = net-positive in >=3 of 4 in-sample quarters; ranking = total net /
        // max drawdown. netTot and maxDD are in net points.
        System.out.printf("%-34s %6s %8s %6s %8s %9s %7s %10s %9s %8s %6s %7s %11s %10s%n",
                "config", "trades", "per-day", "win%", "hitT1%", "netWin%", "avgR", "netAvgPnl",
                "netTot", "maxDD", "posQ", "MAR", "$net/trade", "$net/day");
        for (SweepResult r : results) {
            System.out.printf("%-34s %6d %8.1f %5.0f%% %7.0f%% %8.0f%% %7.2f %10.2f %9.1f %8.1f %6s %7s %11.2f %10.2f%n",
                    r.id, r.trades, r.tradesPerDay, r.winRate * 100,
                    r.hitT1Rate * 100, r.netWinRate * 100,
                    r.avgR, r.netAvgPnl,
                    r.totalNet(), r.maxDrawdown,
                    r.positiveQuarters + "/" + r.quarterCount,
                    Double.isNaN(r.mar()) ? "inf" : String.format("%.2f", r.mar()),
                    r.netAvgPnl * valuePerPoint,
                    r.netAvgPnl * r.tradesPerDay * valuePerPoint);
        }
    }

    @Test
    void gridArmsCarryDistinctExitLaddersRatherThanSharingOne() {
        Map<String, BacktestProperties.Strategy> grid = buildGrid(backtestProperties.getStrategy());

        assertThat(grid).containsKey("control_singleTarget_0.75");
        assertThat(grid.get("control_singleTarget_0.75").getExit().getTiers()).isEmpty();

        BacktestProperties.Strategy none = grid.get("tier3_t3-3.0_none");
        BacktestProperties.Strategy breakeven = grid.get("tier3_t3-3.0_breakeven_after_t1");

        assertThat(none.getExit().getTiers()).hasSize(3);
        assertThat(none.getExit().getRatchet()).isEqualTo(Ratchet.NONE);
        assertThat(breakeven.getExit().getRatchet()).isEqualTo(Ratchet.BREAKEVEN_AFTER_T1);

        // T3 must actually vary across arms.
        assertThat(grid.get("tier3_t3-2.0_none").getExit().getTiers().get(2).getTargetAtrMultiple())
                .isEqualTo(2.0);
        assertThat(grid.get("tier3_t3-4.0_none").getExit().getTiers().get(2).getTargetAtrMultiple())
                .isEqualTo(4.0);
    }

    @Test
    void variantPreservesAnExistingExitLadderFromTheSourceConfig() {
        // Build a source config with a 3-tier ladder and non-default ratchet
        BacktestProperties.Strategy source = new BacktestProperties.Strategy();
        source.setExit(ladder(3.0, Ratchet.BREAKEVEN_AFTER_T1));

        // Pass it through variant() with a mutation unrelated to the exit block
        BacktestProperties.Strategy copy = variant(source, s -> s.setStopAtrMultiple(2.5));

        // Assert the copy has 3 tiers with the same fractions and target multiples
        assertThat(copy.getExit().getTiers()).hasSize(3);
        assertThat(copy.getExit().getTiers().get(0).getFraction()).isEqualTo(1.0 / 3.0);
        assertThat(copy.getExit().getTiers().get(0).getTargetAtrMultiple()).isEqualTo(0.75);
        assertThat(copy.getExit().getTiers().get(1).getFraction()).isEqualTo(1.0 / 3.0);
        assertThat(copy.getExit().getTiers().get(1).getTargetAtrMultiple()).isEqualTo(1.5);
        assertThat(copy.getExit().getTiers().get(2).getFraction()).isEqualTo(1.0 / 3.0);
        assertThat(copy.getExit().getTiers().get(2).getTargetAtrMultiple()).isEqualTo(3.0);

        // Assert the copy has the same ratchet
        assertThat(copy.getExit().getRatchet()).isEqualTo(Ratchet.BREAKEVEN_AFTER_T1);

        // Assert deep copy: mutating source's exit must not affect the copy
        source.setExit(new BacktestProperties.Strategy.Exit());
        assertThat(copy.getExit().getTiers()).hasSize(3);
        assertThat(copy.getExit().getRatchet()).isEqualTo(Ratchet.BREAKEVEN_AFTER_T1);
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

        // Backfill milestones from the tuning log — winners AND known losers, so the first loser-
        // clustering has a contrast set. Run with -Dsweep.persist=true to write to experiments.sqlite.
        // Pre-gate anchor: threshold 3, structure off, long+short, no trend gate (as history ran them).
        BacktestProperties.Strategy pregate = variant(base, s -> {
            s.setConfluenceThreshold(3);
            s.setEnableStructure(false);
            s.setEnableLong(true);
            s.setEnableShort(true);
            s.setTrendEmaPeriod(0);
        });

        // Old 32% baseline (iteration 1): threshold 2, structure on, original geometry.
        grid.put("i1_baseline_th2_struct", variant(base, s -> {
            s.setConfluenceThreshold(2);
            s.setEnableStructure(true);
            s.setEnableShort(true);
            s.setTrendEmaPeriod(0);
            s.setStopAtrMultiple(1.5);
            s.setTargetAtrMultiple(3.0);
        }));
        // Pre-gate win-rate champion (iteration 3): passed in-sample, FAILED out-of-sample on expectancy.
        grid.put("i3_pregate_winchamp_stop4.0_tgt0.5", variant(pregate, s -> {
            s.setProximityAtrMultiple(0.5);
            s.setSwingLookbackBars(8);
            s.setStopAtrMultiple(4.0);
            s.setTargetAtrMultiple(0.5);
        }));
        // Pre-gate expectancy champion (iteration 3).
        grid.put("i3_pregate_expchamp_stop3.0_tgt0.75", variant(pregate, s -> {
            s.setProximityAtrMultiple(0.5);
            s.setSwingLookbackBars(10);
            s.setStopAtrMultiple(3.0);
            s.setTargetAtrMultiple(0.75);
        }));
        // Promoted final candidate (iteration 8): long-only + trend-EMA-200 gate.
        BacktestProperties.Strategy promoted = variant(pregate, s -> {
            s.setEnableShort(false);
            s.setProximityAtrMultiple(0.5);
            s.setSwingLookbackBars(10);
            s.setStopAtrMultiple(3.0);
            s.setTargetAtrMultiple(0.75);
            s.setTrendEmaPeriod(200);
        });
        grid.put("final_longOnly_trend200", promoted);

        // Iteration 10 experiment: dist-to-trend-EMA proximity ceiling (US500 personality lead —
        // the edge lives within ~2 ATR of the EMA). Sweep the ceiling on top of the promoted config
        // to find the level, then validate the winner out-of-sample. maxAtr 0 = the promoted anchor.
        for (double maxAtr : new double[] {1.0, 1.5, 2.0, 2.5, 3.0}) {
            grid.put("emaCeil_%.1fatr".formatted(maxAtr),
                    variant(promoted, s -> s.setTrendEmaMaxAtr(maxAtr)));
        }

        // ---- Stage 1 of the tiered scale-out experiment ----
        // Spec: docs/superpowers/specs/2026-07-21-tiered-scale-out-exits-design.md
        // Anchor is the emaCeil champion. T1/T2 fixed at 0.75/1.5 ATR (T1 is where the ~80% hit
        // rate is already demonstrated -- moving it would change entry edge and exit geometry at
        // once). T3 and the ratchet are swept. The single-target control runs on the same data and
        // the same code, so nothing is compared against historical numbers from another window.
        BacktestProperties.Strategy scaleOutAnchor = variant(promoted, s -> s.setTrendEmaMaxAtr(3.0));

        grid.put("control_singleTarget_0.75", variant(scaleOutAnchor, s -> {
            // Empty ladder == today's all-or-nothing exit. This is the baseline every arm must beat.
        }));

        for (double t3 : new double[] {2.0, 3.0, 4.0}) {
            for (Ratchet ratchet : Ratchet.values()) {
                // Locale.US: the default JVM locale is not guaranteed to use '.' as the decimal
                // separator, and these keys are asserted on verbatim (see the guard test below).
                grid.put(String.format(Locale.US, "tier3_t3-%.1f_%s", t3, ratchet.name().toLowerCase()),
                        variant(scaleOutAnchor, s -> s.setExit(ladder(t3, ratchet))));
            }
        }

        return grid;
    }

    /** A three-equal-thirds ladder at 0.75 / 1.5 / t3 ATR with the given ratchet. */
    private static BacktestProperties.Strategy.Exit ladder(double t3, Ratchet ratchet) {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(1.0 / 3.0, 0.75), tier(1.0 / 3.0, 1.5), tier(1.0 / 3.0, t3)));
        exit.setRatchet(ratchet);
        exit.validate();
        return exit;
    }

    private static BacktestProperties.Strategy.ExitTier tier(double fraction, double target) {
        BacktestProperties.Strategy.ExitTier t = new BacktestProperties.Strategy.ExitTier();
        t.setFraction(fraction);
        t.setTargetAtrMultiple(target);
        return t;
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
        BacktestProperties.Strategy.Exit exitCopy = new BacktestProperties.Strategy.Exit();
        exitCopy.setRatchet(s.getExit().getRatchet());
        List<BacktestProperties.Strategy.ExitTier> tierCopies = new ArrayList<>();
        for (BacktestProperties.Strategy.ExitTier t : s.getExit().getTiers()) {
            tierCopies.add(tier(t.getFraction(), t.getTargetAtrMultiple()));
        }
        exitCopy.setTiers(tierCopies);
        c.setExit(exitCopy);
        return c;
    }

    private record SweepResult(
            String id,
            int trades,
            double tradesPerDay,
            double winRate,
            double netWinRate,
            double hitT1Rate,
            double avgR,
            double avgPnl,
            double netAvgPnl,
            double maxDrawdown,
            long positiveQuarters,
            int quarterCount) {

        /** "Net" = after subtracting one full bid/ask spread per round trip from each trade's pnl. */
        static SweepResult of(String id, List<TradeResult> trades, long tradingDays, double avgSpread) {
            int count = trades.size();
            if (count == 0) {
                return new SweepResult(id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
            long wins = trades.stream().filter(t -> t.pnl() > 0).count();
            long netWins = trades.stream().filter(t -> t.pnl() - avgSpread > 0).count();
            long t1Hits = trades.stream().filter(TradeResult::hitT1).count();
            double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
            double avgPnl = trades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
            return new SweepResult(id, count,
                    tradingDays == 0 ? 0 : (double) count / tradingDays,
                    (double) wins / count,
                    (double) netWins / count,
                    (double) t1Hits / count,
                    avgR, avgPnl, avgPnl - avgSpread,
                    TradeStatistics.maxDrawdown(trades, avgSpread),
                    TradeStatistics.positiveQuarters(trades, avgSpread),
                    TradeStatistics.quarterCount(trades, avgSpread));
        }

        /** Total net points over the run — the numerator of the MAR-style ranking ratio. */
        double totalNet() {
            return netAvgPnl * trades;
        }

        /**
         * MAR-style ratio the spec ranks survivors by: total net ÷ max drawdown (most money, least
         * risk). {@code NaN} when there was no drawdown at all (an infinite ratio has no useful rank).
         */
        double mar() {
            return maxDrawdown == 0 ? Double.NaN : totalNet() / maxDrawdown;
        }
    }
}
