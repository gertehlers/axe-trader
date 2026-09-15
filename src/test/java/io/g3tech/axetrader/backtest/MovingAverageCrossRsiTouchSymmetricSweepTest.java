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
import java.util.List;

/**
 * Isolation test between {@link MovingAverageCrossRsiFilterSpikeTest} (same-bar, symmetric: either
 * extreme confirms either direction) and {@link MovingAverageCrossRsiResetSweepTest} (lookback,
 * DIRECTION-PAIRED: long only accepts a recent oversold touch, short only a recent overbought
 * touch) — the reset sweep changed two things at once and failed at every K, so this isolates just
 * the lookback/touch mechanic while keeping the earlier filter's symmetry: "RSI touched EITHER
 * extreme within the last K bars" confirms a cross in EITHER direction, same as the at-signal
 * filter did, just replacing "is extreme right now" with "touched an extreme recently."
 *
 * <p>If this also underperforms the at-signal filter, the touch/lookback mechanic itself is the
 * problem (not the direction pairing). If it matches or beats the at-signal filter, the pairing
 * was what broke the reset sweep, and touch/lookback deserves a second look with symmetric pairing.
 *
 * <p>Sweeps K in {3,5,8,12} on in-sample, same as the reset sweep, but this time a candidate needs
 * at least {@value #MIN_IN_SAMPLE_TRADES} in-sample trades to be eligible for the OOS confirmation
 * step — the reset sweep's K=3 "winner" was a 62-trade fluke that collapsed OOS; a trade-count
 * floor stops that from happening again.
 *
 * <pre>
 *   ./mvnw test -Dtest=MovingAverageCrossRsiTouchSymmetricSweepTest -Dmacrosstouch=true
 * </pre>
 */
@SpringBootTest
class MovingAverageCrossRsiTouchSymmetricSweepTest {

    private static final int FAST_PERIOD = 7;
    private static final int SLOW_PERIOD = 14;
    private static final double RSI_LOW = 30.0;
    private static final double RSI_HIGH = 70.0;
    private static final int[] LOOKBACKS_TO_SWEEP = {3, 5, 8, 12};
    private static final int MIN_IN_SAMPLE_TRADES = 300;

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
    void sweepSymmetricTouchLookbackInSampleThenConfirmWinnerOutOfSample() {
        Assumptions.assumeTrue(Boolean.getBoolean("macrosstouch"),
                "disabled by default — run with -Dmacrosstouch=true -Dtest=MovingAverageCrossRsiTouchSymmetricSweepTest");

        Window inSample = loadWindow(IN_SAMPLE_FROM, IN_SAMPLE_TO);
        IndicatorBundle indicators = IndicatorBundle.from(inSample.series, backtestProperties.getStrategy());
        ClosePriceIndicator closePrice = indicators.closePrice;
        SMAIndicator fast = new SMAIndicator(closePrice, FAST_PERIOD);
        SMAIndicator slow = new SMAIndicator(closePrice, SLOW_PERIOD);
        Rule crossUp = new CrossedUpIndicatorRule(fast, slow);
        Rule crossDown = new CrossedDownIndicatorRule(fast, slow);

        System.out.printf("%nRSI(7) symmetric touch-lookback sweep on MA(%d/%d) cross (either extreme, either direction, within K bars)%n",
                FAST_PERIOD, SLOW_PERIOD);
        System.out.printf("(net = after one bid/ask spread per round trip; $ at %.2f per point; eligible for OOS needs >=%d in-sample trades)%n%n",
                backtestProperties.getContract().getValuePerPoint(), MIN_IN_SAMPLE_TRADES);
        System.out.printf("%-22s %6s %8s %6s %8s %7s %10s %9s %8s %6s %7s%n",
                "variant", "trades", "per-day", "win%", "netWin%", "avgR", "netAvgPnl", "netTot", "maxDD", "posQ", "MAR");

        // Reference rows.
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

        for (int lookback : LOOKBACKS_TO_SWEEP) {
            NumericIndicator rsiNumeric = NumericIndicator.of(indicators.rsi);
            Rule touchedExtreme = new UnderIndicatorRule(rsiNumeric.lowest(lookback), RSI_LOW)
                    .or(new OverOrEqualIndicatorRule(rsiNumeric.highest(lookback), RSI_HIGH));

            List<TradeResult> trades = runVariant(
                    inSample.series, indicators,
                    crossUp.and(touchedExtreme), crossDown,
                    crossDown.and(touchedExtreme), crossUp);
            String variant = "touch_K" + lookback;
            printRow(variant, trades, inSample);

            Stats stats = Stats.of(trades, inSample.tradingDays, inSample.avgSpread);
            if (stats.count < MIN_IN_SAMPLE_TRADES) {
                continue; // not enough in-sample trades to trust the ranking metric
            }
            double rankMar = Double.isNaN(stats.mar) ? stats.totalNet : stats.mar;
            if (rankMar > bestMar) {
                bestMar = rankMar;
                bestTotalNet = stats.totalNet;
                bestVariant = variant;
                bestK = lookback;
            }
        }

        if (bestVariant == null) {
            System.out.printf("%n-> no candidate reached the %d-trade in-sample floor; nothing eligible for OOS confirmation.%n",
                    MIN_IN_SAMPLE_TRADES);
            return;
        }

        System.out.printf("%n-> in-sample winner: %s (K=%d), MAR=%.2f, netTot=%.1f%n",
                bestVariant, bestK, bestMar, bestTotalNet);
        System.out.println("(picked from the table above; nothing below this line influenced that pick)");

        Window oos = loadWindow(OUT_OF_SAMPLE_FROM, OUT_OF_SAMPLE_TO);
        IndicatorBundle oosIndicators = IndicatorBundle.from(oos.series, backtestProperties.getStrategy());
        ClosePriceIndicator oosClose = oosIndicators.closePrice;
        SMAIndicator oosFast = new SMAIndicator(oosClose, FAST_PERIOD);
        SMAIndicator oosSlow = new SMAIndicator(oosClose, SLOW_PERIOD);
        Rule oosCrossUp = new CrossedUpIndicatorRule(oosFast, oosSlow);
        Rule oosCrossDown = new CrossedDownIndicatorRule(oosFast, oosSlow);
        NumericIndicator oosRsi = NumericIndicator.of(oosIndicators.rsi);
        Rule oosTouchedExtreme = new UnderIndicatorRule(oosRsi.lowest(bestK), RSI_LOW)
                .or(new OverOrEqualIndicatorRule(oosRsi.highest(bestK), RSI_HIGH));

        List<TradeResult> oosTrades = runVariant(
                oos.series, oosIndicators,
                oosCrossUp.and(oosTouchedExtreme), oosCrossDown,
                oosCrossDown.and(oosTouchedExtreme), oosCrossUp);

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
