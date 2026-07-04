package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.PillarVote;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Position;
import org.ta4j.core.Strategy;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.backtest.BarSeriesManager;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Component
public class BacktestRunner {

    private static final int ATR_LOOKBACK = 20;

    /**
     * Runs a single long-only strategy (kept for fixed-rule tests and simple backtests). No config
     * is available, so entry features are not captured on the resulting trades.
     */
    public List<TradeResult> run(BarSeries series, Strategy strategy, IndicatorBundle indicators) {
        return runDirection(series, strategy, indicators, Trade.TradeType.BUY, List.of(), null);
    }

    /**
     * Runs the confluence pair: the bullish strategy as LONG and the bearish strategy as
     * SHORT, then merges both into one timeline sorted by entry time. The pillar votes are
     * re-evaluated at each entry bar to record why the trade was taken, and the {@code config}
     * lets each trade carry its entry {@link TradeFeatures} (swing lookback etc. come from it).
     */
    public List<TradeResult> run(
            BarSeries series, ConfluenceStrategies strategies, IndicatorBundle indicators,
            BacktestProperties.Strategy config) {
        List<TradeResult> results = new ArrayList<>();
        results.addAll(runDirection(
                series, strategies.longStrategy(), indicators, Trade.TradeType.BUY, strategies.bullishVotes(), config));
        results.addAll(runDirection(
                series, strategies.shortStrategy(), indicators, Trade.TradeType.SELL, strategies.bearishVotes(), config));
        results.sort(Comparator.comparing(TradeResult::entryTime));
        return results;
    }

    private List<TradeResult> runDirection(
            BarSeries series,
            Strategy strategy,
            IndicatorBundle indicators,
            Trade.TradeType entryType,
            List<PillarVote> votes,
            BacktestProperties.Strategy config) {
        TradingRecord record = new BarSeriesManager(series).run(strategy, entryType);
        List<TradeResult> results = new ArrayList<>();

        for (Position position : record.getPositions()) {
            if (position.isClosed()) {
                results.add(toTradeResult(series, indicators, position, votes, config));
            }
        }

        return results;
    }

    private TradeResult toTradeResult(
            BarSeries series, IndicatorBundle indicators, Position position, List<PillarVote> votes,
            BacktestProperties.Strategy config) {
        Trade entry = position.getEntry();

        int entryIndex = entry.getIndex();
        double entryPrice = entry.getPricePerAsset(series).doubleValue();
        Direction direction = direction(entry);
        double atrAtEntry = indicators.atr.getValue(entryIndex).doubleValue();

        int exitIndex;
        double exitPrice;
        ExitReason exitReason;
        double riskPerUnit;

        if (config == null) {
            // Legacy fixed-rule path (no stop/target config): trust ta4j's close-based exit and
            // normalise R by ATR, as before. Kept for the simple non-confluence backtests/tests.
            Trade exit = position.getExit();
            exitIndex = exit.getIndex();
            exitPrice = exit.getPricePerAsset(series).doubleValue();
            exitReason = classifyExit(series, exitIndex, pnl(direction, entryPrice, exitPrice));
            riskPerUnit = atrAtEntry;
        } else {
            // Confluence path: model the stop/target as a fixed bracket posted at entry (entry price
            // ± multiple x ATR-at-entry, the way a real OCO order rests) and fill AT the level on the
            // first bar whose intrabar range touches it — not at bar close. This removes the
            // optimism of the old close-based rules (which never stopped out on a bar that pierced
            // the stop but closed back inside, and filled past the level on gap bars). R is measured
            // against the actual stop distance, so a target win is +targetAtr/stopAtr R, not inflated.
            double stopDist = config.getStopAtrMultiple() * atrAtEntry;
            double targetDist = config.getTargetAtrMultiple() * atrAtEntry;
            ExitOutcome outcome = intrabarExit(
                    series, direction, entryIndex, entryPrice, stopDist, targetDist,
                    config.getMaxHoldingBars());
            exitIndex = outcome.index();
            exitPrice = outcome.price();
            exitReason = outcome.reason();
            riskPerUnit = stopDist == 0.0 ? atrAtEntry : stopDist;
        }

        double pnl = pnl(direction, entryPrice, exitPrice);
        double rMultiple = riskPerUnit == 0.0 ? 0.0 : pnl / riskPerUnit;
        List<String> reasons = reasonsAt(votes, entryIndex);

        return new TradeResult(
                series.getBar(entryIndex).getEndTime().atZone(ZoneOffset.UTC),
                series.getBar(exitIndex).getEndTime().atZone(ZoneOffset.UTC),
                direction,
                entryPrice,
                exitPrice,
                pnl,
                rMultiple,
                classifyVolatility(indicators, entryIndex),
                pnl > 0.0,
                exitReason,
                config == null ? null : featuresAt(series, indicators, config, entryIndex, reasons.size()),
                reasons);
    }

    /**
     * Walks bars forward from the fill and returns the first stop/target/time exit, filled at the
     * bracket <em>level</em> (not bar close). Tie-break is deliberately <b>conservative</b>: when a
     * single bar's high–low range spans both the stop and the target, OHLC cannot tell which was
     * touched first, so the stop is assumed — we never book an intrabar win we can't prove. A
     * position that touches neither before {@code maxHoldingBars} exits at that bar's close (TIME);
     * one that survives to the last bar is force-closed at the last close (END).
     */
    static ExitOutcome intrabarExit(
            BarSeries series, Direction direction, int entryIndex, double entryPrice,
            double stopDist, double targetDist, int maxHoldingBars) {
        double stopLevel = direction == Direction.LONG ? entryPrice - stopDist : entryPrice + stopDist;
        double targetLevel = direction == Direction.LONG ? entryPrice + targetDist : entryPrice - targetDist;
        int lastIndex = series.getEndIndex();

        for (int i = entryIndex + 1; i <= lastIndex; i++) {
            Bar bar = series.getBar(i);
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();

            boolean stopHit = direction == Direction.LONG ? low <= stopLevel : high >= stopLevel;
            boolean targetHit = direction == Direction.LONG ? high >= targetLevel : low <= targetLevel;

            if (stopHit) {
                return new ExitOutcome(i, stopLevel, ExitReason.STOP);
            }
            if (targetHit) {
                return new ExitOutcome(i, targetLevel, ExitReason.TARGET);
            }
            if (maxHoldingBars > 0 && (i - entryIndex) >= maxHoldingBars) {
                return new ExitOutcome(i, bar.getClosePrice().doubleValue(), ExitReason.TIME);
            }
        }

        return new ExitOutcome(lastIndex, series.getBar(lastIndex).getClosePrice().doubleValue(), ExitReason.END);
    }

    /** One resolved exit: the bar it happened on, the fill price, and why. */
    record ExitOutcome(int index, double price, ExitReason reason) {
    }

    /**
     * Computes the entry feature vector at the signal bar ({@code entryIndex - 1}, the bar the votes
     * agreed on — ta4j fills on the next bar), using only backward-looking data. Distances are in
     * ATR units so they compare across volatility regimes.
     */
    private static TradeFeatures featuresAt(
            BarSeries series, IndicatorBundle ind, BacktestProperties.Strategy config,
            int entryIndex, int confluenceScore) {
        int i = Math.max(0, entryIndex - 1);
        double atr = ind.atr.getValue(i).doubleValue();
        double denom = atr == 0.0 ? Double.NaN : atr;
        double close = ind.closePrice.getValue(i).doubleValue();

        double distBbLower = (close - ind.bbLower.getValue(i).doubleValue()) / denom;
        double distBbUpper = (ind.bbUpper.getValue(i).doubleValue() - close) / denom;

        int lookback = Math.max(1, config.getSwingLookbackBars());
        int start = Math.max(0, i - lookback + 1);
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int j = start; j <= i; j++) {
            double c = ind.closePrice.getValue(j).doubleValue();
            lowest = Math.min(lowest, c);
            highest = Math.max(highest, c);
        }
        double distSupport = (close - lowest) / denom;
        double distResistance = (highest - close) / denom;

        Double distTrendEma = ind.trendEma == null
                ? null
                : (close - ind.trendEma.getValue(i).doubleValue()) / denom;

        var slopeEma = ind.trendEma != null ? ind.trendEma : ind.ema;
        int k = 10;
        int back = Math.max(0, i - k);
        int span = Math.max(1, i - back);
        double slope = (slopeEma.getValue(i).doubleValue() - slopeEma.getValue(back).doubleValue()) / (span * denom);

        double volSma = ind.volumeSma.getValue(i).doubleValue();
        double volumeRatio = volSma == 0.0 ? Double.NaN : ind.volume.getValue(i).doubleValue() / volSma;

        var time = series.getBar(entryIndex).getEndTime().atZone(ZoneOffset.UTC);
        return new TradeFeatures(
                ind.rsi.getValue(i).doubleValue(),
                distBbLower, distBbUpper, distSupport, distResistance,
                distTrendEma, slope, atr,
                atrPercentile(ind, i, 100),
                volumeRatio,
                time.getHour(), time.getDayOfWeek().getValue(), confluenceScore);
    }

    /** Rank of the ATR at {@code index} within the trailing {@code window} bars, in [0, 1]. */
    private static double atrPercentile(IndicatorBundle indicators, int index, int window) {
        int start = Math.max(0, index - window + 1);
        double current = indicators.atr.getValue(index).doubleValue();
        int countBelow = 0;
        int total = 0;
        for (int j = start; j <= index; j++) {
            if (indicators.atr.getValue(j).doubleValue() <= current) {
                countBelow++;
            }
            total++;
        }
        return total == 0 ? 0.0 : (double) countBelow / total;
    }

    /**
     * Legacy exit classifier for the no-config fixed-rule path only (the confluence path now gets an
     * exact reason from {@link #intrabarExit}). The fixed-rule exit fires on bar close, so the pnl
     * sign approximates the trigger; a position still open at the last bar is {@link ExitReason#END}.
     */
    private static ExitReason classifyExit(BarSeries series, int exitIndex, double pnl) {
        if (exitIndex >= series.getEndIndex()) {
            return ExitReason.END;
        }
        return pnl > 0.0 ? ExitReason.TARGET : ExitReason.STOP;
    }

    /**
     * The confluence signal fires on the bar before the fill: ta4j's default execution model
     * evaluates {@code shouldOperate(signalBar)} and opens the position on the next bar. So the
     * reasons are read from {@code entryIndex - 1}, the bar the votes actually agreed on.
     */
    private static List<String> reasonsAt(List<PillarVote> votes, int entryIndex) {
        int signalIndex = Math.max(0, entryIndex - 1);
        List<String> reasons = new ArrayList<>();
        for (PillarVote vote : votes) {
            if (vote.rule().isSatisfied(signalIndex)) {
                reasons.add(vote.name());
            }
        }
        return reasons;
    }

    private static Direction direction(Trade entry) {
        return entry.getType() == Trade.TradeType.BUY ? Direction.LONG : Direction.SHORT;
    }

    private static double pnl(Direction direction, double entryPrice, double exitPrice) {
        if (direction == Direction.LONG) {
            return exitPrice - entryPrice;
        }
        return entryPrice - exitPrice;
    }

    private static VolatilityRegime classifyVolatility(IndicatorBundle indicators, int entryIndex) {
        int start = Math.max(0, entryIndex - ATR_LOOKBACK + 1);
        List<Double> atrValues = new ArrayList<>();
        for (int i = start; i <= entryIndex; i++) {
            atrValues.add(indicators.atr.getValue(i).doubleValue());
        }
        Collections.sort(atrValues);

        double entryAtr = indicators.atr.getValue(entryIndex).doubleValue();
        double lowThreshold = percentile(atrValues, 0.2);
        double highThreshold = percentile(atrValues, 0.8);

        if (entryAtr <= lowThreshold) {
            return VolatilityRegime.LOW;
        }
        if (entryAtr >= highThreshold) {
            return VolatilityRegime.HIGH;
        }
        return VolatilityRegime.NORMAL;
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.floor((sortedValues.size() - 1) * percentile);
        return sortedValues.get(index);
    }
}
