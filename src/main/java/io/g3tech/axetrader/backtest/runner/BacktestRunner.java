package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.PillarVote;
import org.springframework.stereotype.Component;
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
     * Runs a single long-only strategy (kept for fixed-rule tests and simple backtests).
     */
    public List<TradeResult> run(BarSeries series, Strategy strategy, IndicatorBundle indicators) {
        return runDirection(series, strategy, indicators, Trade.TradeType.BUY, List.of());
    }

    /**
     * Runs the confluence pair: the bullish strategy as LONG and the bearish strategy as
     * SHORT, then merges both into one timeline sorted by entry time. The pillar votes are
     * re-evaluated at each entry bar to record why the trade was taken.
     */
    public List<TradeResult> run(BarSeries series, ConfluenceStrategies strategies, IndicatorBundle indicators) {
        List<TradeResult> results = new ArrayList<>();
        results.addAll(runDirection(
                series, strategies.longStrategy(), indicators, Trade.TradeType.BUY, strategies.bullishVotes()));
        results.addAll(runDirection(
                series, strategies.shortStrategy(), indicators, Trade.TradeType.SELL, strategies.bearishVotes()));
        results.sort(Comparator.comparing(TradeResult::entryTime));
        return results;
    }

    private List<TradeResult> runDirection(
            BarSeries series,
            Strategy strategy,
            IndicatorBundle indicators,
            Trade.TradeType entryType,
            List<PillarVote> votes) {
        TradingRecord record = new BarSeriesManager(series).run(strategy, entryType);
        List<TradeResult> results = new ArrayList<>();

        for (Position position : record.getPositions()) {
            if (position.isClosed()) {
                results.add(toTradeResult(series, indicators, position, votes));
            }
        }

        return results;
    }

    private TradeResult toTradeResult(
            BarSeries series, IndicatorBundle indicators, Position position, List<PillarVote> votes) {
        Trade entry = position.getEntry();
        Trade exit = position.getExit();

        int entryIndex = entry.getIndex();
        int exitIndex = exit.getIndex();
        double entryPrice = entry.getPricePerAsset(series).doubleValue();
        double exitPrice = exit.getPricePerAsset(series).doubleValue();
        Direction direction = direction(entry);
        double pnl = pnl(direction, entryPrice, exitPrice);
        double risk = indicators.atr.getValue(entryIndex).doubleValue();
        double rMultiple = risk == 0.0 ? 0.0 : pnl / risk;

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
                classifyExit(series, exitIndex, pnl),
                reasonsAt(votes, entryIndex));
    }

    /**
     * Classifies why the position closed. The confluence exit rule is
     * {@code stopLoss OR stopGain OR timeStop}, and stopGain only fires in profit / stopLoss only
     * in loss — so for a mid-series exit the pnl sign identifies the trigger. A position still open
     * at the last bar is force-closed ({@link ExitReason#END}).
     *
     * <p>TIME exits are not distinguished here (this method has no access to {@code max-holding-bars});
     * with the time stop disabled — as in the promoted profile and every tuned candidate — no TIME
     * exits occur, so the classification is exact. When a config enables the time stop, the
     * experiment-writer (which has the config) will refine this; see the design spec.
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
