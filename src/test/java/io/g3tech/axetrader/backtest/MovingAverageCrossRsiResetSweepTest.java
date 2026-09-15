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
import org.ta4j.core.indicators.numeric.NumericIndicator;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverOrEqualIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Follow-up to {@link MovingAverageCrossRsiFilterSpikeTest}: that filter required RSI(7) to be
 * extreme <em>at the cross bar itself</em>. The owner's read of the price action was different —
 * RSI spikes to an extreme, pulls back toward neutral, THEN the MA cross fires and the move runs.
 * A same-bar threshold can't see that; this test instead requires RSI to have <em>touched</em> an
 * extreme within a lookback window before the cross, and pairs the extreme with direction (a
 * bullish cross wants a recent oversold touch — "bought the dip"; a bearish cross wants a recent
 * overbought touch — "sold the rally") rather than treating either extreme as confirmation for
 * either direction the way the previous filter did.
 *
 * <p>Sweeps the lookback K on in-sample only, ranks by MAR (total net / max drawdown, same
 * ranking as {@code ConfluenceSweepTest}), then runs ONLY the in-sample winner on out-of-sample
 * once — no retuning against OOS. Baseline (no filter) and the previous same-bar 30/70 filter are
 * included in the in-sample table purely as reference points, not as sweep candidates.
 *
 * <pre>
 *   ./mvnw test -Dtest=MovingAverageCrossRsiResetSweepTest -Dmacrossreset=true
 * </pre>
 */
@SpringBootTest
class MovingAverageCrossRsiResetSweepTest {

    private static final int FAST_PERIOD = 7;
    private static final int SLOW_PERIOD = 14;
    private static final double RSI_LOW = 30.0;
    private static final double RSI_HIGH = 70.0;
    private static final int[] LOOKBACKS_TO_SWEEP = {3, 5, 8, 12};

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
    void sweepLookbackInSampleThenConfirmWinnerOutOfSample() {
        Assumptions.assumeTrue(Boolean.getBoolean("macrossreset"),
                "disabled by default — run with -Dmacrossreset=true -Dtest=MovingAverageCrossRsiResetSweepTest");

        Window inSample = loadWindow(IN_SAMPLE_FROM, IN_SAMPLE_TO);
        IndicatorBundle indicators = IndicatorBundle.from(inSample.series, backtestProperties.getStrategy());
        ClosePriceIndicator closePrice = indicators.closePrice;
        SMAIndicator fast = new SMAIndicator(closePrice, FAST_PERIOD);
        SMAIndicator slow = new SMAIndicator(closePrice, SLOW_PERIOD);
        Rule crossUp = new CrossedUpIndicatorRule(fast, slow);
        Rule crossDown = new CrossedDownIndicatorRule(fast, slow);

        System.out.printf("%nRSI(7)-reset lookback sweep on MA(%d/%d) cross (long <- recent oversold <%.0f, short <- recent overbought >=%.0f)%n",
                FAST_PERIOD, SLOW_PERIOD, RSI_LOW, RSI_HIGH);
        System.out.printf("(net = after one bid/ask spread per round trip; $ at %.2f per point)%n%n",
                backtestProperties.getContract().getValuePerPoint());
        System.out.printf("%-22s %6s %8s %6s %8s %7s %10s %9s %8s %6s %7s%n",
                "variant", "trades", "per-day", "win%", "netWin%", "avgR", "netAvgPnl", "netTot", "maxDD", "posQ", "MAR");

        // Reference rows (not sweep candidates): unfiltered baseline and the previous same-bar filter.
        printRow("baseline", runVariant(inSample.series, indicators, crossUp, crossDown, crossDown, crossUp), inSample);
        Rule atSignalExtreme = new UnderIndicatorRule(indicators.rsi, RSI_LOW)
                .or(new OverOrEqualIndicatorRule(indicators.rsi, RSI_HIGH));
        printRow("atSignal_30_70", runVariant(
                inSample.series, indicators, crossUp.and(atSignalExtreme), crossDown,
                crossDown.and(atSignalExtreme), crossUp), inSample);

        String bestVariant = null;
        int bestK = -1;
        double bestMar = Double.NEGATIVE_INFINITY;
        double bestTotalNet = Double.NEGATIVE_INFINITY;
        List<TradeResult> bestTrades = null;

        for (int lookback : LOOKBACKS_TO_SWEEP) {
            NumericIndicator rsiNumeric = NumericIndicator.of(indicators.rsi);
            Rule recentlyOversold = new UnderIndicatorRule(rsiNumeric.lowest(lookback), RSI_LOW);
            Rule recentlyOverbought = new OverOrEqualIndicatorRule(rsiNumeric.highest(lookback), RSI_HIGH);

            List<TradeResult> trades = runVariant(
                    inSample.series, indicators,
                    crossUp.and(recentlyOversold), crossDown,
                    crossDown.and(recentlyOverbought), crossUp);
            String variant = "reset_K" + lookback;
            printRow(variant, trades, inSample);

            Stats stats = Stats.of(trades, inSample.tradingDays, inSample.avgSpread);
            double rankMar = Double.isNaN(stats.mar) ? stats.totalNet : stats.mar;
            // Rank by MAR when there's a real drawdown to divide by; fall back to total net (this
            // only kicks in for a near-empty, essentially untested variant, not the real winner).
            if (rankMar > bestMar || (Double.isNaN(stats.mar) && stats.totalNet > bestTotalNet)) {
                bestMar = rankMar;
                bestTotalNet = stats.totalNet;
                bestVariant = variant;
                bestK = lookback;
                bestTrades = trades;
            }
        }

        System.out.printf("%n-> in-sample winner: %s (K=%d), MAR=%.2f, netTot=%.1f%n",
                bestVariant, bestK, bestMar, bestTotalNet);
        System.out.println("(picked from the table above; nothing below this line influenced that pick)");

        // Confirm ONLY the in-sample winner on out-of-sample. No sweep here — sweeping OOS and
        // reporting the best would just be tuning against OOS under a different name.
        Window oos = loadWindow(OUT_OF_SAMPLE_FROM, OUT_OF_SAMPLE_TO);
        IndicatorBundle oosIndicators = IndicatorBundle.from(oos.series, backtestProperties.getStrategy());
        ClosePriceIndicator oosClose = oosIndicators.closePrice;
        SMAIndicator oosFast = new SMAIndicator(oosClose, FAST_PERIOD);
        SMAIndicator oosSlow = new SMAIndicator(oosClose, SLOW_PERIOD);
        Rule oosCrossUp = new CrossedUpIndicatorRule(oosFast, oosSlow);
        Rule oosCrossDown = new CrossedDownIndicatorRule(oosFast, oosSlow);
        NumericIndicator oosRsi = NumericIndicator.of(oosIndicators.rsi);
        Rule oosRecentlyOversold = new UnderIndicatorRule(oosRsi.lowest(bestK), RSI_LOW);
        Rule oosRecentlyOverbought = new OverOrEqualIndicatorRule(oosRsi.highest(bestK), RSI_HIGH);

        List<TradeResult> oosTrades = runVariant(
                oos.series, oosIndicators,
                oosCrossUp.and(oosRecentlyOversold), oosCrossDown,
                oosCrossDown.and(oosRecentlyOverbought), oosCrossUp);

        System.out.printf("%nOOS confirmation for %s (K=%d), no further tuning:%n", bestVariant, bestK);
        printRow("OOS_" + bestVariant, oosTrades, oos);
    }

    private List<TradeResult> runVariant(
            BarSeries series, IndicatorBundle indicators, Rule longEntry, Rule longExit, Rule shortEntry, Rule shortExit) {
        Strategy longStrategy = new BaseStrategy("MA_CROSS_LONG", longEntry, longExit);
        longStrategy.setUnstableBars(SLOW_PERIOD);
        Strategy shortStrategy = new BaseStrategy("MA_CROSS_SHORT", shortEntry, shortExit);
        shortStrategy.setUnstableBars(SLOW_PERIOD);

        ConfluenceStrategies strategies = new ConfluenceStrategies(longStrategy, shortStrategy, List.of(), List.of());
        return backtestRunner.run(series, strategies, indicators, null);
    }

    private void printRow(String variant, List<TradeResult> trades, Window window) {
        Stats s = Stats.of(trades, window.tradingDays, window.avgSpread);
        if (s.count == 0) {
            System.out.printf("%-22s %6d%n", variant, 0);
            return;
        }
        System.out.printf("%-22s %6d %8.1f %5.0f%% %7.0f%% %7.2f %10.2f %9.1f %8.1f %6s %7s%n",
                variant, s.count, s.tradesPerDay, s.winRate * 100, s.netWinRate * 100,
                s.avgR, s.netAvgPnl, s.totalNet, s.maxDrawdown,
                s.positiveQuarters + "/" + s.quarterCount,
                Double.isNaN(s.mar) ? "inf" : String.format("%.2f", s.mar));
    }

    private record Stats(
            int count, double tradesPerDay, double winRate, double netWinRate, double avgR,
            double netAvgPnl, double totalNet, double maxDrawdown, long positiveQuarters, int quarterCount, double mar) {

        static Stats of(List<TradeResult> trades, long tradingDays, double avgSpread) {
            int count = trades.size();
            if (count == 0) {
                return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Double.NaN);
            }
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
            double mar = maxDrawdown == 0 ? Double.NaN : totalNet / maxDrawdown;
            return new Stats(count, tradesPerDay, (double) wins / count, (double) netWins / count,
                    avgR, netAvgPnl, totalNet, maxDrawdown, positiveQuarters, quarterCount, mar);
        }
    }

    private Window loadWindow(Instant from, Instant to) {
        List<HistoricalPrice> allPrices = repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                backtestProperties.getEpic(), from, to);
        List<HistoricalPrice> prices = new ArrayList<>(allPrices).stream().filter(this::hasSaneSpread).toList();

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
