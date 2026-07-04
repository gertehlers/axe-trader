package io.g3tech.axetrader.backtest.strategy;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.ConfluenceScoreIndicator;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.numeric.NumericIndicator;
import org.ta4j.core.rules.AverageTrueRangeStopGainRule;
import org.ta4j.core.rules.AverageTrueRangeStopLossRule;
import org.ta4j.core.rules.BooleanIndicatorRule;
import org.ta4j.core.rules.BooleanRule;
import org.ta4j.core.rules.OpenedPositionMinimumBarCountRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.OverOrEqualIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;
import org.ta4j.core.rules.UnderOrEqualIndicatorRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 5-pillar confluence strategies. Each enabled pillar contributes a named bullish
 * and bearish {@link PillarVote}; the votes are counted by a {@link ConfluenceScoreIndicator}
 * and an entry fires once the score reaches {@code confluence-threshold}.
 *
 * <p>Pillars:
 * <ol>
 *   <li>Technical indicators — RSI extreme + Bollinger Band touch</li>
 *   <li>Candlestick patterns — engulfing / harami / hammer / shooting-star</li>
 *   <li>Support &amp; resistance — price near the lookback low (bull) / high (bear)</li>
 *   <li>Chart-pattern structure — break above the prior lookback high (bull) / below the prior low (bear)</li>
 *   <li>Volume / trend — above-average volume in the direction of the EMA trend</li>
 * </ol>
 */
@Component
public class StrategyFactory {

    public ConfluenceStrategies build(IndicatorBundle indicators, BacktestProperties.Strategy config) {
        List<PillarVote> bullishVotes = new ArrayList<>();
        List<PillarVote> bearishVotes = new ArrayList<>();

        NumericIndicator close = NumericIndicator.of(indicators.closePrice);
        NumericIndicator atrBand = NumericIndicator.of(indicators.atr).multipliedBy(config.getProximityAtrMultiple());

        // Pillar 1 — Technical indicators: RSI extreme confirmed by a Bollinger Band touch.
        bullishVotes.add(new PillarVote("RSI+BB",
                new UnderIndicatorRule(indicators.rsi, config.getRsiOversold())
                        .and(new UnderOrEqualIndicatorRule(indicators.closePrice, indicators.bbLower))));
        bearishVotes.add(new PillarVote("RSI+BB",
                new OverIndicatorRule(indicators.rsi, config.getRsiOverbought())
                        .and(new OverOrEqualIndicatorRule(indicators.closePrice, indicators.bbUpper))));

        // Pillar 2 — Candlestick patterns.
        if (config.isEnableCandles()) {
            bullishVotes.add(new PillarVote("Candle",
                    new BooleanIndicatorRule(indicators.bullishEngulfing)
                            .or(new BooleanIndicatorRule(indicators.bullishHarami))
                            .or(new BooleanIndicatorRule(indicators.hammer))));
            bearishVotes.add(new PillarVote("Candle",
                    new BooleanIndicatorRule(indicators.bearishEngulfing)
                            .or(new BooleanIndicatorRule(indicators.bearishHarami))
                            .or(new BooleanIndicatorRule(indicators.shootingStar))));
        }

        // Backward-only swing levels (no look-ahead): lowest/highest close over the lookback window.
        int lookback = config.getSwingLookbackBars();
        NumericIndicator supportLevel = close.lowest(lookback);
        NumericIndicator resistanceLevel = close.highest(lookback);

        // Pillar 3 — Support & resistance: close within proximityAtrMultiple x ATR of a level.
        if (config.isEnableSupportResistance()) {
            Rule nearSupport = close.minus(supportLevel).abs().isLessThan(atrBand);
            Rule nearResistance = close.minus(resistanceLevel).abs().isLessThan(atrBand);
            bullishVotes.add(new PillarVote("S/R", nearSupport));
            bearishVotes.add(new PillarVote("S/R", nearResistance));
        }

        // Pillar 4 — Chart-pattern structure proxy: break of the prior lookback range.
        if (config.isEnableStructure()) {
            NumericIndicator priorHigh = resistanceLevel.previous(1);
            NumericIndicator priorLow = supportLevel.previous(1);
            bullishVotes.add(new PillarVote("Structure", close.isGreaterThan(priorHigh)));
            bearishVotes.add(new PillarVote("Structure", close.isLessThan(priorLow)));
        }

        // Pillar 5 — Volume / trend confirmation: above-average volume aligned with the EMA trend.
        if (config.isEnableVolumeTrend()) {
            Rule highVolume = new OverIndicatorRule(indicators.volume, indicators.volumeSma);
            bullishVotes.add(new PillarVote("Vol+Trend",
                    highVolume.and(new OverIndicatorRule(indicators.closePrice, indicators.ema))));
            bearishVotes.add(new PillarVote("Vol+Trend",
                    highVolume.and(new UnderIndicatorRule(indicators.closePrice, indicators.ema))));
        }

        // Hard higher-timeframe trend gate (not a vote): mean-reversion longs only above the
        // trend EMA ("buy the dip in an uptrend"), shorts only below it. Cuts counter-trend
        // knife-catching, which the tuning log showed drives the losing quarters.
        Rule longGate = null;
        Rule shortGate = null;
        if (config.getTrendEmaPeriod() > 0) {
            longGate = new OverIndicatorRule(indicators.closePrice, indicators.trendEma);
            shortGate = new UnderIndicatorRule(indicators.closePrice, indicators.trendEma);

            // Proximity ceiling: the US500 personality slices (TODO.md, 2026-07-04) show the edge
            // lives within ~2 ATR of the trend EMA — extended entries bleed. When set, require the
            // close to be no more than trendEmaMaxAtr x ATR away from the EMA (still on the gated
            // side), so we "buy the dip near the EMA" instead of chasing extension.
            if (config.getTrendEmaMaxAtr() > 0) {
                NumericIndicator trendEma = NumericIndicator.of(indicators.trendEma);
                NumericIndicator ceiling = NumericIndicator.of(indicators.atr)
                        .multipliedBy(config.getTrendEmaMaxAtr());
                // close within [trendEma, trendEma + ceiling] for longs; mirror for shorts.
                longGate = longGate.and(close.isLessThan(trendEma.plus(ceiling)));
                shortGate = shortGate.and(close.isGreaterThan(trendEma.minus(ceiling)));
            }

            // Regime-slope gate (iteration 13): the price-vs-EMA gate above is same-timeframe and
            // whipsaws in choppy/down regimes (the Dec'24 / Q1'25 losers), so "above the EMA" still
            // buys into downtrends. Require the trend EMA itself to be RISING over the last N bars
            // for longs (FALLING for shorts) — a coarse, few-parameter higher-timeframe regime proxy
            // that sits out sustained downtrends instead of slicing in-sample loser features.
            if (config.getTrendEmaSlopeLookback() > 0) {
                NumericIndicator trendEma = NumericIndicator.of(indicators.trendEma);
                NumericIndicator prior = trendEma.previous(config.getTrendEmaSlopeLookback());
                longGate = longGate.and(trendEma.isGreaterThan(prior));
                shortGate = shortGate.and(trendEma.isLessThan(prior));
            }
        }
        if (!config.isEnableLong()) {
            longGate = BooleanRule.FALSE;
        }
        if (!config.isEnableShort()) {
            shortGate = BooleanRule.FALSE;
        }

        Strategy longStrategy = directionStrategy("CONFLUENCE_LONG", indicators, config, bullishVotes, longGate);
        Strategy shortStrategy = directionStrategy("CONFLUENCE_SHORT", indicators, config, bearishVotes, shortGate);
        return new ConfluenceStrategies(longStrategy, shortStrategy, bullishVotes, bearishVotes);
    }

    private Strategy directionStrategy(
            String name, IndicatorBundle indicators, BacktestProperties.Strategy config,
            List<PillarVote> votes, Rule trendGate) {
        List<Rule> rules = votes.stream().map(PillarVote::rule).toList();
        var score = new ConfluenceScoreIndicator(indicators.series, rules);
        Rule entry = new OverIndicatorRule(score, config.getConfluenceThreshold() - 0.5);
        if (trendGate != null) {
            entry = entry.and(trendGate);
        }

        Rule exit = new AverageTrueRangeStopLossRule(
                indicators.closePrice, indicators.atr, config.getStopAtrMultiple())
                .or(new AverageTrueRangeStopGainRule(
                        indicators.closePrice, indicators.atr, config.getTargetAtrMultiple()));
        if (config.getMaxHoldingBars() > 0) {
            // Time stop: with a tight target and wide stop, trades that hit neither level drift
            // for hours carrying full stop-size tail risk — cap the holding time instead.
            exit = exit.or(new OpenedPositionMinimumBarCountRule(config.getMaxHoldingBars()));
        }

        Strategy strategy = new BaseStrategy(name, entry, exit);
        strategy.setUnstableBars(Math.max(config.getEmaPeriod(), config.getTrendEmaPeriod()));
        return strategy;
    }
}
