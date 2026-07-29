package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.config.Ratchet;
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
import java.util.Objects;

@Component
public class BacktestRunner {

    private static final int ATR_LOOKBACK = 20;
    private final EntryFeatureExtractor entryFeatureExtractor;

    public BacktestRunner(EntryFeatureExtractor entryFeatureExtractor) {
        this.entryFeatureExtractor = Objects.requireNonNull(entryFeatureExtractor, "entryFeatureExtractor");
    }

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
        int tiersFilled = 0;
        boolean hitT1 = false;

        if (config == null) {
            // Legacy fixed-rule path (no stop/target config): trust ta4j's close-based exit and
            // normalise R by ATR, as before. Kept for the simple non-confluence backtests/tests.
            Trade exit = position.getExit();
            exitIndex = exit.getIndex();
            exitPrice = exit.getPricePerAsset(series).doubleValue();
            exitReason = classifyExit(series, exitIndex, pnl(direction, entryPrice, exitPrice));
            riskPerUnit = atrAtEntry;
            tiersFilled = exitReason == ExitReason.TARGET ? 1 : 0;
            hitT1 = tiersFilled == 1;
        } else {
            // Confluence path: model the stop/target as a bracket posted at entry and fill AT the
            // level intrabar. With a tier ladder configured, bank a fraction at each rung and
            // ratchet the stop; with no ladder, this is one 100% tier at targetAtrMultiple, which
            // is exactly the previous behaviour.
            double stopDist = config.getStopAtrMultiple() * atrAtEntry;
            List<TierLevel> tiers = tierLevels(config, atrAtEntry);

            TieredExitOutcome outcome = tieredExit(
                    series, direction, entryIndex, entryPrice, stopDist, tiers,
                    config.getExit().getRatchet(), config.getMaxHoldingBars());

            exitIndex = outcome.index();
            exitPrice = outcome.weightedPrice();
            exitReason = outcome.finalReason();
            riskPerUnit = stopDist == 0.0 ? atrAtEntry : stopDist;
            tiersFilled = outcome.tiersFilled();
            hitT1 = outcome.hitT1();
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
                config == null ? null : entryFeatureExtractor.at(
                        series, indicators, config, entryIndex - 1, reasons.size()),
                reasons,
                tiersFilled,
                hitT1);
    }

    /**
     * Converts the configured ladder (ATR multiples) into engine tiers (price distances). An empty
     * ladder yields the single 100% tier at {@code targetAtrMultiple} — today's behaviour.
     */
    private static List<TierLevel> tierLevels(
            BacktestProperties.Strategy config, double atrAtEntry) {
        List<BacktestProperties.Strategy.ExitTier> configured = config.getExit().getTiers();
        if (configured.isEmpty()) {
            return List.of(new TierLevel(1.0, config.getTargetAtrMultiple() * atrAtEntry));
        }
        List<TierLevel> levels = new ArrayList<>(configured.size());
        for (BacktestProperties.Strategy.ExitTier tier : configured) {
            levels.add(new TierLevel(
                    tier.getFraction(), tier.getTargetAtrMultiple() * atrAtEntry));
        }
        return levels;
    }

    /**
     * Walks bars forward from the fill and returns the first stop/target/time exit, filled at the
     * bracket <em>level</em> (not bar close). Retained for the single-target path and for callers
     * that want one outcome; implemented as a one-tier {@link #tieredExit} ladder so there is a
     * single exit engine to reason about.
     */
    static ExitOutcome intrabarExit(
            BarSeries series, Direction direction, int entryIndex, double entryPrice,
            double stopDist, double targetDist, int maxHoldingBars) {
        TieredExitOutcome out = tieredExit(
                series, direction, entryIndex, entryPrice, stopDist,
                List.of(new TierLevel(1.0, targetDist)), Ratchet.NONE, maxHoldingBars);
        TierFill only = out.fills().get(out.fills().size() - 1);
        return new ExitOutcome(only.index(), only.price(), only.reason());
    }

    /**
     * Scale-out exit: walks bars forward banking a fraction of the position at each tier level and
     * ratcheting the stop as tiers fill.
     *
     * <p><b>Conservative tie-break.</b> When one bar's range spans the current stop and any unfilled
     * tier, OHLC cannot reveal the order, so the stop is assumed and <b>no tier banks on that
     * bar</b> — we never book an intrabar win we cannot prove.
     *
     * <p><b>Ratchet timing.</b> A ratchet triggered by a tier filling on bar {@code i} takes effect
     * from bar {@code i+1}. Within the filling bar itself OHLC cannot show whether the ratcheted
     * level was touched before or after the tier.
     *
     * @param tiers ascending target distances in price units (not ATR multiples), fractions summing
     *              to 1.0; must not be empty (enforced by this method)
     * @throws IllegalArgumentException if {@code tiers} is empty
     */
    static TieredExitOutcome tieredExit(
            BarSeries series, Direction direction, int entryIndex, double entryPrice,
            double stopDist, List<TierLevel> tiers, Ratchet ratchet, int maxHoldingBars) {
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                series, direction, entryIndex, entryPrice, stopDist,
                tiers.stream().map(tier -> new TieredExitEngine.TierLevel(tier.fraction(), tier.targetDist())).toList(),
                ratchet, maxHoldingBars);
        return new TieredExitOutcome(
                outcome.fills().stream().map(fill -> new TierFill(
                        fill.index(), fill.price(), fill.fraction(), fill.reason())).toList(),
                outcome.tiersFilled(), outcome.hitT1());
    }

    /** One resolved exit: the bar it happened on, the fill price, and why. */
    record ExitOutcome(int index, double price, ExitReason reason) {
    }

    /** One rung of the ladder, in price distance from entry (not ATR multiples). */
    record TierLevel(double fraction, double targetDist) {
    }

    /** One tranche closing: which bar, at what price, how much of the position, and why. */
    record TierFill(int index, double price, double fraction, ExitReason reason) {
    }

    /**
     * The full scale-out result. {@code weightedPrice()} is the size-weighted average fill, which —
     * because pnl is linear in price and the fractions sum to 1 — yields exactly the size-weighted
     * sum of the per-tier pnls when passed to {@code pnl(direction, entryPrice, ...)}.
     */
    record TieredExitOutcome(List<TierFill> fills, int tiersFilled, boolean hitT1) {

        /** The bar the position finished closing on. */
        int index() {
            return fills.get(fills.size() - 1).index();
        }

        double weightedPrice() {
            double sum = 0.0;
            for (TierFill fill : fills) {
                sum += fill.fraction() * fill.price();
            }
            return sum;
        }

        /** Reason the final tranche closed. */
        ExitReason finalReason() {
            return fills.get(fills.size() - 1).reason();
        }
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
