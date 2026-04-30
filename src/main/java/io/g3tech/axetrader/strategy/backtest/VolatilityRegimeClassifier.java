package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.CandleWindow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class VolatilityRegimeClassifier {

    private final double normalThreshold;
    private final double elevatedThreshold;

    private VolatilityRegimeClassifier(double normalThreshold, double elevatedThreshold) {
        this.normalThreshold = normalThreshold;
        this.elevatedThreshold = elevatedThreshold;
    }

    static VolatilityRegimeClassifier from(List<Candle> candles, int minimumCandles, io.g3tech.axetrader.strategy.IndicatorCalculator indicatorCalculator) {
        var atrPercents = new ArrayList<Double>();

        for (int i = minimumCandles - 1; i < candles.size(); i++) {
            var window = new CandleWindow(candles.subList(0, i + 1));
            var indicators = indicatorCalculator.calculate(window);
            var close = candles.get(i).closeValue();
            if (close != 0) {
                atrPercents.add(indicators.atr14() / close);
            }
        }

        if (atrPercents.isEmpty()) {
            return new VolatilityRegimeClassifier(Double.MAX_VALUE, Double.MAX_VALUE);
        }

        atrPercents.sort(Comparator.naturalOrder());
        return new VolatilityRegimeClassifier(
                percentile(atrPercents, 0.50),
                percentile(atrPercents, 0.80)
        );
    }

    VolatilityRegime classify(double atrPercent) {
        if (atrPercent <= normalThreshold) {
            return VolatilityRegime.NORMAL;
        }
        if (atrPercent <= elevatedThreshold) {
            return VolatilityRegime.ELEVATED;
        }

        return VolatilityRegime.EXTREME;
    }

    private static double percentile(List<Double> values, double percentile) {
        var index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }
}
