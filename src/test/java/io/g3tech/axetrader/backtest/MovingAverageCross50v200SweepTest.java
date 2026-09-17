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
 * Slower MA pair on the timeframe that actually worked: MA(50/200) instead of MA(7/14), still on
 * 5-minute candles (the 1m version of the 7/14 pair was a clean loser at every RSI setting tried —
 * see {@link MovingAverageCross1mSweepTest} — so this goes the other direction: slow the cross
 * down instead of speeding the candles up). A 50/200 pair fires far less often than 7/14, so this
 * is closer to a trend filter than a scalping signal; expect a much lower trade count, which is why
 * the in-sample trade-count floor here is {@value #MIN_IN_SAMPLE_TRADES}, not the 300 used for the
 * fast pair's sweeps — a 50/200 cross is inherently low-frequency by design, not by accident.
 *
 * <p>Sweeps RSI extremity threshold and touch-lookback K exactly like
 * {@link MovingAverageCross1mSweepTest}, same in-sample/out-of-sample boundary, same "pick winner
 * on in-sample only, confirm once on OOS" discipline.
 *
 * <pre>
 *   ./mvnw test -Dtest=MovingAverageCross50v200SweepTest -Dmacross50v200=true
 * </pre>
 */
@SpringBootTest
class MovingAverageCross50v200SweepTest {

    private static final int FAST_PERIOD = 50;
    private static final int SLOW_PERIOD = 200;
    private static final int TIMEFRAME_MINUTES = 5;
    private static final int[] LOOKBACKS_TO_SWEEP = {3, 5, 8, 12};
    private static final double[] THRESHOLDS_TO_SWEEP = {20, 25, 30, 35};
    private static final int MIN_IN_SAMPLE_TRADES = 40;

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
    void sweepFiftyTwoHundredCross() {
        Assumptions.assumeTrue(Boolean.getBoolean("macross50v200"),
                "disabled by default — run with -Dmacross50v200=true -Dtest=MovingAverageCross50v200SweepTest");

        Window inSample = loadWindow(IN_SAMPLE_FROM, IN_SAMPLE_TO);
        IndicatorBundle indicators = IndicatorBundle.from(inSample.series, backtestProperties.getStrategy());
        ClosePriceIndicator closePrice = indicators.closePrice;
        SMAIndicator fast = new SMAIndicator(closePrice, FAST_PERIOD);
        SMAIndicator slow = new SMAIndicator(closePrice, SLOW_PERIOD);
        Rule crossUp = new CrossedUpIndicatorRule(fast, slow);
        Rule crossDown = new CrossedDownIndicatorRule(fast, slow);

        System.out.printf("%nMA(%d/%d) cross on %dm candles, %d bars, %d trading days, avg spread %.2f pts%n",
                FAST_PERIOD, SLOW_PERIOD, TIMEFRAME_MINUTES, inSample.series.getBarCount(), inSample.tradingDays,
                inSample.avgSpread);
        System.out.printf("(net = after one bid/ask spread per round trip; $ at %.2f per point; eligible for OOS needs >=%d in-sample trades)%n%n",
                backtestProperties.getContract().getValuePerPoint(), MIN_IN_SAMPLE_TRADES);
        System.out.printf("%-24s %6s %8s %6s %8s %7s %10s %9s %8s %6s %7s%n",
                "variant", "trades", "per-day", "win%", "netWin%", "avgR", "netAvgPnl", "netTot", "maxDD", "posQ", "MAR");

        printRow("baseline", runVariant(inSample.series, indicators, crossUp, crossDown, crossDown, crossUp), inSample);

        Best best = new Best();
        for (double lowThreshold : THRESHOLDS_TO_SWEEP) {
            double highThreshold = 100 - lowThreshold;
            Rule atSignalExtreme = new UnderIndicatorRule(indicators.rsi, lowThreshold)
                    .or(new OverOrEqualIndicatorRule(indicators.rsi, highThreshold));
            String variant = "atSignal_%.0f_%.0f".formatted(lowThreshold, highThreshold);
            List<TradeResult> trades = runVariant(
                    inSample.series, indicators, crossUp.and(atSignalExtreme), crossDown,
                    crossDown.and(atSignalExtreme), crossUp);
            printRow(variant, trades, inSample);
            best.consider(variant, -1, lowThreshold, trades, inSample);

            for (int lookback : LOOKBACKS_TO_SWEEP) {
                NumericIndicator rsiNumeric = NumericIndicator.of(indicators.rsi);
                Rule touchedExtreme = new UnderIndicatorRule(rsiNumeric.lowest(lookback), lowThreshold)
                        .or(new OverOrEqualIndicatorRule(rsiNumeric.highest(lookback), highThreshold));
                String touchVariant = "touch_K%d_%.0f_%.0f".formatted(lookback, lowThreshold, highThreshold);
                List<TradeResult> touchTrades = runVariant(
                        inSample.series, indicators, crossUp.and(touchedExtreme), crossDown,
                        crossDown.and(touchedExtreme), crossUp);
                printRow(touchVariant, touchTrades, inSample);
                best.consider(touchVariant, lookback, lowThreshold, touchTrades, inSample);
            }
        }

        if (best.variant == null) {
            System.out.printf("%n-> no candidate reached the %d-trade in-sample floor; nothing eligible for OOS confirmation.%n",
                    MIN_IN_SAMPLE_TRADES);
            return;
        }

        System.out.printf("%n-> in-sample winner: %s, MAR=%.2f, netTot=%.1f%n", best.variant, best.mar, best.totalNet);
        System.out.println("(picked from the table above; nothing below this line influenced that pick)");

        Window oos = loadWindow(OUT_OF_SAMPLE_FROM, OUT_OF_SAMPLE_TO);
        IndicatorBundle oosIndicators = IndicatorBundle.from(oos.series, backtestProperties.getStrategy());
        ClosePriceIndicator oosClose = oosIndicators.closePrice;
        SMAIndicator oosFast = new SMAIndicator(oosClose, FAST_PERIOD);
        SMAIndicator oosSlow = new SMAIndicator(oosClose, SLOW_PERIOD);
        Rule oosCrossUp = new CrossedUpIndicatorRule(oosFast, oosSlow);
        Rule oosCrossDown = new CrossedDownIndicatorRule(oosFast, oosSlow);
        double lowThreshold = best.threshold;
        double highThreshold = 100 - lowThreshold;
        Rule oosExtreme;
        if (best.lookback < 0) {
            oosExtreme = new UnderIndicatorRule(oosIndicators.rsi, lowThreshold)
                    .or(new OverOrEqualIndicatorRule(oosIndicators.rsi, highThreshold));
        } else {
            NumericIndicator oosRsi = NumericIndicator.of(oosIndicators.rsi);
            oosExtreme = new UnderIndicatorRule(oosRsi.lowest(best.lookback), lowThreshold)
                    .or(new OverOrEqualIndicatorRule(oosRsi.highest(best.lookback), highThreshold));
        }
        List<TradeResult> oosTrades = runVariant(
                oos.series, oosIndicators, oosCrossUp.and(oosExtreme), oosCrossDown,
                oosCrossDown.and(oosExtreme), oosCrossUp);

        System.out.printf("%nOOS confirmation for %s, no further tuning:%n", best.variant);
        printRow("OOS_" + best.variant, oosTrades, oos);

        // Sanity check: the RSI sweep screened 20 candidates on thin in-sample samples (many under
        // 200 trades) before picking a "winner" -- also check the plain unfiltered baseline OOS,
        // since it's the one candidate not selected by screening the other 20 against each other.
        List<TradeResult> oosBaseline = runVariant(oos.series, oosIndicators, oosCrossUp, oosCrossDown, oosCrossDown, oosCrossUp);
        System.out.println("\nFor comparison, the plain unfiltered baseline on OOS (not screened, not selected):");
        printRow("OOS_baseline", oosBaseline, oos);
    }

    private final class Best {
        String variant;
        int lookback;
        double threshold;
        double mar = Double.NEGATIVE_INFINITY;
        double totalNet = Double.NEGATIVE_INFINITY;

        void consider(String variant, int lookback, double threshold, List<TradeResult> trades, Window window) {
            Stats stats = Stats.of(trades, window.tradingDays, window.avgSpread);
            if (stats.count < MIN_IN_SAMPLE_TRADES) {
                return;
            }
            double rankMar = Double.isNaN(stats.mar) ? stats.totalNet : stats.mar;
            if (rankMar > this.mar) {
                this.mar = rankMar;
                this.totalNet = stats.totalNet;
                this.variant = variant;
                this.lookback = lookback;
                this.threshold = threshold;
            }
        }
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
            System.out.printf("%-24s %6d%n", variant, 0);
            return;
        }
        System.out.printf("%-24s %6d %8.1f %5.0f%% %7.0f%% %7.2f %10.2f %9.1f %8.1f %6s %7s%n",
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
        BarSeries series = barSeriesFactory.fromPrices(backtestProperties.getEpic(), prices, TIMEFRAME_MINUTES);
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
