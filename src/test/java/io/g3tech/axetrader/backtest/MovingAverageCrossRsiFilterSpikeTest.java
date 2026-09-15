package io.g3tech.axetrader.backtest;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.experiment.TradeStatistics;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.BacktestRunner;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverOrEqualIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Follow-up to {@link MovingAverageCrossSpikeTest}: that spike's informational RSI(7)-at-signal
 * breakdown (run over the FULL dataset, so contaminated by looking at the "out-of-sample" period
 * too) suggested crosses confirmed by RSI &lt;30 or &gt;=70 outperform crosses in the 30-50 "weak
 * momentum" band. This test promotes that observation to an actual entry filter and checks it the
 * honest way: tuned/eyeballed on in-sample only, then run ONCE on out-of-sample with zero changes.
 *
 * <p>Same IN_SAMPLE/OOS boundary as {@code ConfluenceSweepTest} (2024-12-04 -> 2026-01-01 in-sample,
 * 2026-01-01 onward out-of-sample) so this hypothesis is held to the same discipline as the main
 * strategy line.
 *
 * <p>The filter gates entries only, not exits: exits still fire on the plain opposite cross
 * (unconditional). So unlike the baseline spike, this variant is <b>not</b> always-in-market — a
 * cross that isn't RSI-confirmed just leaves the position flat until the next qualifying cross.
 *
 * <pre>
 *   ./mvnw test -Dtest=MovingAverageCrossRsiFilterSpikeTest -Dmacrossrsi=true
 * </pre>
 */
@SpringBootTest
class MovingAverageCrossRsiFilterSpikeTest {

    private static final int FAST_PERIOD = 7;
    private static final int SLOW_PERIOD = 14;
    private static final double RSI_LOW = 30.0;
    private static final double RSI_HIGH = 70.0;

    /** Same boundary as ConfluenceSweepTest: don't reinvent what "in-sample" means for this dataset. */
    private static final Instant IN_SAMPLE_FROM = Instant.parse("2024-12-04T00:00:00Z");
    private static final Instant IN_SAMPLE_TO = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant OUT_OF_SAMPLE_FROM = IN_SAMPLE_TO;
    private static final Instant OUT_OF_SAMPLE_TO = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private HistoricalPriceRepository repository;

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private BacktestRunner backtestRunner;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void rsiExtremityFilterInSampleThenOutOfSample() {
        Assumptions.assumeTrue(Boolean.getBoolean("macrossrsi"),
                "disabled by default — run with -Dmacrossrsi=true -Dtest=MovingAverageCrossRsiFilterSpikeTest");

        System.out.printf("%nRSI(7)-extremity filter on MA(%d/%d) cross: entry only when RSI<%.0f or RSI>=%.0f%n",
                FAST_PERIOD, SLOW_PERIOD, RSI_LOW, RSI_HIGH);
        System.out.printf("(net = after one bid/ask spread per round trip; $ at %.2f per point)%n%n",
                backtestProperties.getContract().getValuePerPoint());
        System.out.printf("%-12s %-10s %6s %8s %6s %8s %7s %10s %9s %8s %6s%n",
                "window", "variant", "trades", "per-day", "win%", "netWin%", "avgR", "netAvgPnl", "netTot", "maxDD", "posQ");

        runWindow("in-sample", IN_SAMPLE_FROM, IN_SAMPLE_TO);
        runWindow("OOS", OUT_OF_SAMPLE_FROM, OUT_OF_SAMPLE_TO);
    }

    private void runWindow(String windowLabel, Instant from, Instant to) {
        Window window = loadWindow(from, to);
        if (window == null) {
            System.out.printf("%-12s (no data in %s -> %s)%n", windowLabel, from, to);
            return;
        }

        IndicatorBundle indicators = IndicatorBundle.from(window.series, backtestProperties.getStrategy());
        ClosePriceIndicator closePrice = indicators.closePrice;
        SMAIndicator fast = new SMAIndicator(closePrice, FAST_PERIOD);
        SMAIndicator slow = new SMAIndicator(closePrice, SLOW_PERIOD);
        Rule crossUp = new CrossedUpIndicatorRule(fast, slow);
        Rule crossDown = new CrossedDownIndicatorRule(fast, slow);

        List<TradeResult> baseline = runVariant(window.series, indicators, crossUp, crossDown, crossDown, crossUp);
        print(windowLabel, "baseline", baseline, window.tradingDays, window.avgSpread);
        if (windowLabel.equals("in-sample")) {
            // Leak check: the 30/70 thresholds were picked by eyeballing buckets computed over the
            // FULL dataset in the original spike, which included the OOS window below. Recompute the
            // same breakdown restricted to in-sample-only baseline trades to see whether the same
            // "extremes beat the middle" shape would have been visible from in-sample data alone.
            printRsiBucketBreakdown(baseline, window.series, indicators);
        }

        Rule rsiExtreme = new UnderIndicatorRule(indicators.rsi, RSI_LOW)
                .or(new OverOrEqualIndicatorRule(indicators.rsi, RSI_HIGH));
        List<TradeResult> filtered = runVariant(
                window.series, indicators, crossUp.and(rsiExtreme), crossDown, crossDown.and(rsiExtreme), crossUp);
        print(windowLabel, "rsiFiltered", filtered, window.tradingDays, window.avgSpread);
    }

    private List<TradeResult> runVariant(
            BarSeries series, IndicatorBundle indicators, Rule longEntry, Rule longExit, Rule shortEntry, Rule shortExit) {
        Strategy longStrategy = new BaseStrategy("MA_CROSS_LONG", longEntry, longExit);
        longStrategy.setUnstableBars(SLOW_PERIOD);
        Strategy shortStrategy = new BaseStrategy("MA_CROSS_SHORT", shortEntry, shortExit);
        shortStrategy.setUnstableBars(SLOW_PERIOD);

        ConfluenceStrategies strategies = new ConfluenceStrategies(longStrategy, shortStrategy, List.of(), List.of());
        // config == null: trust ta4j's own exit (the opposite cross), not an ATR stop/target bracket.
        return backtestRunner.run(series, strategies, indicators, null);
    }

    private void print(String windowLabel, String variant, List<TradeResult> trades, long tradingDays, double avgSpread) {
        if (trades.isEmpty()) {
            System.out.printf("%-12s %-10s %6d%n", windowLabel, variant, 0);
            return;
        }
        int count = trades.size();
        long wins = trades.stream().filter(TradeResult::isWin).count();
        long netWins = trades.stream().filter(t -> t.pnl() - avgSpread > 0).count();
        double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
        double avgPnl = trades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
        double netAvgPnl = avgPnl - avgSpread;
        double totalNet = netAvgPnl * count;
        double maxDrawdown = TradeStatistics.maxDrawdown(trades, avgSpread);
        long positiveQuarters = TradeStatistics.positiveQuarters(trades, avgSpread);
        int quarterCount = TradeStatistics.quarterCount(trades, avgSpread);
        double tradesPerDay = tradingDays == 0 ? 0 : (double) count / tradingDays;

        System.out.printf("%-12s %-10s %6d %8.1f %5.0f%% %7.0f%% %7.2f %10.2f %9.1f %8.1f %6s%n",
                windowLabel, variant, count, tradesPerDay, wins * 100.0 / count, netWins * 100.0 / count,
                avgR, netAvgPnl, totalNet, maxDrawdown, positiveQuarters + "/" + quarterCount);
    }

    private void printRsiBucketBreakdown(List<TradeResult> trades, BarSeries series, IndicatorBundle indicators) {
        java.util.Map<Instant, Integer> indexByEndTime = new java.util.HashMap<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            indexByEndTime.put(series.getBar(i).getEndTime(), i);
        }
        java.util.Map<String, List<TradeResult>> byBucket = trades.stream().collect(java.util.stream.Collectors.groupingBy(
                t -> rsiBucket(rsiAtSignal(t, indexByEndTime, indicators)), java.util.TreeMap::new, java.util.stream.Collectors.toList()));
        System.out.println("  in-sample-only RSI(7)-at-signal breakdown (leak check for the 30/70 cutoff):");
        for (var entry : byBucket.entrySet()) {
            List<TradeResult> bucketTrades = entry.getValue();
            long bucketWins = bucketTrades.stream().filter(TradeResult::isWin).count();
            double bucketAvgPnl = bucketTrades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
            System.out.printf("    RSI %-8s %5d trades  win %3.0f%%  avgPnl %+7.2f%n",
                    entry.getKey(), bucketTrades.size(), 100.0 * bucketWins / bucketTrades.size(), bucketAvgPnl);
        }
    }

    private static double rsiAtSignal(TradeResult trade, java.util.Map<Instant, Integer> indexByEndTime, IndicatorBundle indicators) {
        Integer entryIndex = indexByEndTime.get(trade.entryTime().toInstant());
        int signalIndex = Math.max(0, (entryIndex == null ? 0 : entryIndex) - 1);
        return indicators.rsi.getValue(signalIndex).doubleValue();
    }

    private static String rsiBucket(double rsi) {
        if (rsi < RSI_LOW) {
            return "<30";
        }
        if (rsi < 50) {
            return "30-50";
        }
        if (rsi < RSI_HIGH) {
            return "50-70";
        }
        return ">=70";
    }

    private Window loadWindow(Instant from, Instant to) {
        List<HistoricalPrice> allPrices = repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                backtestProperties.getEpic(), from, to);
        if (allPrices.isEmpty()) {
            return null;
        }
        List<HistoricalPrice> prices = allPrices.stream().filter(this::hasSaneSpread).toList();

        double avgSpread = prices.stream()
                .mapToDouble(p -> p.getCloseAsk() - p.getCloseBid())
                .average()
                .orElse(0.0);
        BarSeries series = barSeriesFactory.fromPrices(
                backtestProperties.getEpic(), prices, backtestProperties.getTimeframeMinutes());
        long tradingDays = series.getBarData().stream()
                .map(bar -> bar.getEndTime().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();
        return new Window(series, avgSpread, tradingDays);
    }

    private boolean hasSaneSpread(HistoricalPrice p) {
        return p.getOpenAsk() >= p.getOpenBid() && p.getHighAsk() >= p.getHighBid()
                && p.getLowAsk() >= p.getLowBid() && p.getCloseAsk() >= p.getCloseBid();
    }

    private record Window(BarSeries series, double avgSpread, long tradingDays) {
    }
}
