package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
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
import java.util.List;

@Component
public class BacktestRunner {

    private static final int ATR_LOOKBACK = 20;

    public List<TradeResult> run(BarSeries series, Strategy strategy, IndicatorBundle indicators) {
        TradingRecord record = new BarSeriesManager(series).run(strategy);
        List<TradeResult> results = new ArrayList<>();

        for (Position position : record.getPositions()) {
            if (position.isClosed()) {
                results.add(toTradeResult(series, indicators, position));
            }
        }

        return results;
    }

    private TradeResult toTradeResult(BarSeries series, IndicatorBundle indicators, Position position) {
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
                pnl > 0.0);
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
