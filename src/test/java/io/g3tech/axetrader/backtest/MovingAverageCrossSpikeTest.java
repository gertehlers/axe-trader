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
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike (throwaway): "does a plain always-in-market 7/14 SMA cross on 5m US500 make money?"
 * RSI(7) is computed and logged per trade for informational eyeballing only — it never gates
 * entry or exit. See {@code docs/handoffs/} for none; this is a one-shot answer, not a kept
 * feature. Disabled during normal {@code mvnw test} runs.
 *
 * <pre>
 *   ./mvnw test -Dtest=MovingAverageCrossSpikeTest -Dmacross=true
 * </pre>
 *
 * <p>Uses the no-bracket {@code BacktestRunner} path (config == null) so each leg exits exactly
 * on the opposite cross rather than an ATR stop/target bracket — the two legs' entries and exits
 * land on the same bars (the crosses strictly alternate), so the merged timeline is continuous:
 * always in a position, flipping direction on every cross.
 */
@SpringBootTest
class MovingAverageCrossSpikeTest {

    private static final int FAST_PERIOD = 7;
    private static final int SLOW_PERIOD = 14;
    private static final int RSI_PERIOD = 7;

    @Autowired
    private HistoricalPriceRepository repository;

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Autowired
    private BacktestRunner backtestRunner;

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void alwaysInMarketMaCrossSpike() {
        Assumptions.assumeTrue(Boolean.getBoolean("macross"),
                "disabled by default — run with -Dmacross=true -Dtest=MovingAverageCrossSpikeTest");

        String epic = backtestProperties.getEpic();
        int timeframeMinutes = backtestProperties.getTimeframeMinutes();

        List<HistoricalPrice> allPrices = repository.findByEpic(
                epic, Sort.by(Sort.Direction.ASC, "snapshotTimeUtc"), Limit.of(600_000));
        assertThat(allPrices).isNotEmpty();

        // main's checkout lacks the price_exclusion ledger the cleaner worktrees use to drop known
        // bad ticks; filter the ~0.1% of rows with an inverted bid/ask here instead of touching
        // BarSeriesFactory's validation for a throwaway spike.
        List<HistoricalPrice> prices = allPrices.stream().filter(MovingAverageCrossSpikeTest::hasSaneSpread).toList();
        int droppedRows = allPrices.size() - prices.size();
        allPrices = null;

        double avgSpread = prices.stream()
                .mapToDouble(p -> p.getCloseAsk() - p.getCloseBid())
                .average()
                .orElse(0.0);

        BarSeries series = barSeriesFactory.fromPrices(epic, prices, timeframeMinutes);
        Instant from = prices.get(0).getSnapshotTimeUtc();
        Instant to = prices.get(prices.size() - 1).getSnapshotTimeUtc();
        prices = null; // 500k 1m rows no longer needed

        long tradingDays = series.getBarData().stream()
                .map(bar -> bar.getEndTime().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();

        System.out.printf(
                "%nMA(%d/%d) cross spike, %s %dm bars, %s -> %s: %d bars, %d trading days, avg spread %.2f pts, dropped %d bad-tick rows%n%n",
                FAST_PERIOD, SLOW_PERIOD, epic, timeframeMinutes, from, to, series.getBarCount(), tradingDays, avgSpread,
                droppedRows);

        // RSI(7) and ATR come from the existing confluence config (rsi-period is already 7 in
        // application.yaml) — reused purely for indicator values, no confluence rules involved.
        IndicatorBundle indicators = IndicatorBundle.from(series, backtestProperties.getStrategy());

        ClosePriceIndicator closePrice = indicators.closePrice;
        SMAIndicator fast = new SMAIndicator(closePrice, FAST_PERIOD);
        SMAIndicator slow = new SMAIndicator(closePrice, SLOW_PERIOD);

        Rule crossUp = new CrossedUpIndicatorRule(fast, slow);
        Rule crossDown = new CrossedDownIndicatorRule(fast, slow);

        Strategy longStrategy = new BaseStrategy("MA_CROSS_LONG", crossUp, crossDown);
        longStrategy.setUnstableBars(SLOW_PERIOD);
        Strategy shortStrategy = new BaseStrategy("MA_CROSS_SHORT", crossDown, crossUp);
        shortStrategy.setUnstableBars(SLOW_PERIOD);

        ConfluenceStrategies maCross = new ConfluenceStrategies(longStrategy, shortStrategy, List.of(), List.of());
        // config == null: trust ta4j's own exit (the opposite cross), not an ATR stop/target bracket.
        List<TradeResult> trades = backtestRunner.run(series, maCross, indicators, null);

        assertThat(trades).isNotEmpty();

        Map<Instant, Integer> indexByEndTime = new HashMap<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            indexByEndTime.put(series.getBar(i).getEndTime(), i);
        }

        double valuePerPoint = backtestProperties.getContract().getValuePerPoint();
        long wins = trades.stream().filter(TradeResult::isWin).count();
        long netWins = trades.stream().filter(t -> t.pnl() - avgSpread > 0).count();
        double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
        double avgPnl = trades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
        double netAvgPnl = avgPnl - avgSpread;
        double totalNet = netAvgPnl * trades.size();
        double maxDrawdown = TradeStatistics.maxDrawdown(trades, avgSpread);
        long positiveQuarters = TradeStatistics.positiveQuarters(trades, avgSpread);
        int quarterCount = TradeStatistics.quarterCount(trades, avgSpread);
        double tradesPerDay = tradingDays == 0 ? 0 : (double) trades.size() / tradingDays;

        System.out.printf("(net = after one bid/ask spread per round trip; $ at %.2f per point)%n", valuePerPoint);
        System.out.printf("%-20s %6d%n", "trades", trades.size());
        System.out.printf("%-20s %6.1f%n", "trades/day", tradesPerDay);
        System.out.printf("%-20s %5.0f%%%n", "win% (raw)", 100.0 * wins / trades.size());
        System.out.printf("%-20s %5.0f%%%n", "win% (net)", 100.0 * netWins / trades.size());
        System.out.printf("%-20s %6.2f%n", "avg R", avgR);
        System.out.printf("%-20s %6.2f pts%n", "avg pnl (raw)", avgPnl);
        System.out.printf("%-20s %6.2f pts%n", "avg pnl (net)", netAvgPnl);
        System.out.printf("%-20s %6.1f pts (%.2f)%n", "total net", totalNet, totalNet * valuePerPoint);
        System.out.printf("%-20s %6.1f pts (%.2f)%n", "max drawdown", maxDrawdown, maxDrawdown * valuePerPoint);
        System.out.printf("%-20s %s/%d%n", "positive quarters", positiveQuarters, quarterCount);
        System.out.printf("%-20s %s%n%n", "MAR (net/maxDD)", maxDrawdown == 0 ? "inf" : String.format("%.2f", totalNet / maxDrawdown));

        printBreakdown("direction", trades, t -> t.direction().name(), avgSpread);
        printBreakdown("quarter", trades,
                t -> t.entryTime().getYear() + "Q" + ((t.entryTime().getMonthValue() - 1) / 3 + 1), avgSpread);

        // RSI(7)-at-signal is informational only — never wired into a rule. Bucket it after the
        // fact to see whether it separates winners from losers, per trade result already produced
        // above; this is the "eyes propose, data disposes" check on whether it's worth promoting
        // to an actual filter later.
        System.out.println("\nRSI(7)-at-signal vs outcome (informational only, not a filter):");
        Map<String, List<TradeResult>> byRsiBucket = trades.stream().collect(Collectors.groupingBy(
                t -> rsiBucket(rsiAtSignal(t, indexByEndTime, indicators)), TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<TradeResult>> entry : byRsiBucket.entrySet()) {
            List<TradeResult> bucketTrades = entry.getValue();
            long bucketWins = bucketTrades.stream().filter(TradeResult::isWin).count();
            double bucketAvgPnl = bucketTrades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
            System.out.printf("  RSI %-8s %5d trades  win %3.0f%%  avgPnl %+7.2f%n",
                    entry.getKey(), bucketTrades.size(), 100.0 * bucketWins / bucketTrades.size(), bucketAvgPnl);
        }
    }

    private static boolean hasSaneSpread(HistoricalPrice p) {
        return p.getOpenAsk() >= p.getOpenBid() && p.getHighAsk() >= p.getHighBid()
                && p.getLowAsk() >= p.getLowBid() && p.getCloseAsk() >= p.getCloseBid();
    }

    /** RSI(7) one bar before the fill — the bar the actual cross happened on. */
    private static double rsiAtSignal(TradeResult trade, Map<Instant, Integer> indexByEndTime, IndicatorBundle indicators) {
        Integer entryIndex = indexByEndTime.get(trade.entryTime().toInstant());
        int signalIndex = Math.max(0, (entryIndex == null ? 0 : entryIndex) - 1);
        return indicators.rsi.getValue(signalIndex).doubleValue();
    }

    private static String rsiBucket(double rsi) {
        if (rsi < 30) {
            return "<30";
        }
        if (rsi < 50) {
            return "30-50";
        }
        if (rsi < 70) {
            return "50-70";
        }
        return ">=70";
    }

    private static void printBreakdown(
            String label, List<TradeResult> trades, Function<TradeResult, String> keyFn, double avgSpread) {
        Map<String, List<TradeResult>> groups = trades.stream()
                .collect(Collectors.groupingBy(keyFn, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<TradeResult>> group : groups.entrySet()) {
            List<TradeResult> groupTrades = group.getValue();
            long wins = groupTrades.stream().filter(t -> t.pnl() > 0).count();
            double avgPnl = groupTrades.stream().mapToDouble(TradeResult::pnl).average().orElse(0);
            System.out.printf("  %-10s %-8s %5d trades  win %3.0f%%  netAvgPnl %+7.2f%n",
                    label, group.getKey(), groupTrades.size(),
                    100.0 * wins / groupTrades.size(), avgPnl - avgSpread);
        }
    }
}
