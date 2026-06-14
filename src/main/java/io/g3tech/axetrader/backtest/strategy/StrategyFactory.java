package io.g3tech.axetrader.backtest.strategy;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.rules.AverageTrueRangeStopGainRule;
import org.ta4j.core.rules.AverageTrueRangeStopLossRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderOrEqualIndicatorRule;

@Component
public class StrategyFactory {

    public Strategy build(IndicatorBundle indicators, BacktestProperties.Strategy config) {
        Rule longEntry = new CrossedUpIndicatorRule(indicators.rsi, config.getRsiOversold())
                .and(new OverIndicatorRule(indicators.closePrice, indicators.ema))
                .and(new UnderOrEqualIndicatorRule(indicators.closePrice, indicators.bbLower));

        Rule exit = new AverageTrueRangeStopLossRule(
                indicators.closePrice, indicators.atr, config.getStopAtrMultiple())
                .or(new AverageTrueRangeStopGainRule(
                        indicators.closePrice, indicators.atr, config.getTargetAtrMultiple()));

        Strategy strategy = new BaseStrategy("RSI_BB_EMA_LONG", longEntry, exit);
        strategy.setUnstableBars(config.getEmaPeriod());
        return strategy;
    }
}
