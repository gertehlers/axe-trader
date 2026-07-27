package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Computes the runner's backward-looking feature snapshot at a completed signal bar.
 *
 * <p>The caller supplies the signal index directly. No value at a later index is read.
 */
@Component
public final class EntryFeatureExtractor {

    /**
     * Computes the existing trade feature definitions at {@code signalIndex}.
     */
    public TradeFeatures at(
            BarSeries series,
            IndicatorBundle indicators,
            BacktestProperties.Strategy config,
            int signalIndex,
            int confluenceScore) {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(indicators, "indicators");
        Objects.requireNonNull(config, "config");
        if (signalIndex < series.getBeginIndex() || signalIndex > series.getEndIndex()) {
            throw new IndexOutOfBoundsException("signalIndex outside series: " + signalIndex);
        }

        double atr = indicators.atr.getValue(signalIndex).doubleValue();
        double denominator = atr == 0.0 ? Double.NaN : atr;
        double close = indicators.closePrice.getValue(signalIndex).doubleValue();

        double distanceToBbLower =
                (close - indicators.bbLower.getValue(signalIndex).doubleValue()) / denominator;
        double distanceToBbUpper =
                (indicators.bbUpper.getValue(signalIndex).doubleValue() - close) / denominator;

        int lookback = Math.max(1, config.getSwingLookbackBars());
        int start = Math.max(series.getBeginIndex(), signalIndex - lookback + 1);
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int index = start; index <= signalIndex; index++) {
            double value = indicators.closePrice.getValue(index).doubleValue();
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
        }
        double distanceToSupport = (close - lowest) / denominator;
        double distanceToResistance = (highest - close) / denominator;

        Double distanceToTrendEma = indicators.trendEma == null
                ? null
                : (close - indicators.trendEma.getValue(signalIndex).doubleValue()) / denominator;

        var slopeEma = indicators.trendEma != null ? indicators.trendEma : indicators.ema;
        int back = Math.max(series.getBeginIndex(), signalIndex - 10);
        int span = Math.max(1, signalIndex - back);
        double slope = (slopeEma.getValue(signalIndex).doubleValue()
                - slopeEma.getValue(back).doubleValue()) / (span * denominator);

        double volumeSma = indicators.volumeSma.getValue(signalIndex).doubleValue();
        double volumeRatio = volumeSma == 0.0
                ? Double.NaN
                : indicators.volume.getValue(signalIndex).doubleValue() / volumeSma;

        var signalTime = series.getBar(signalIndex).getEndTime().atZone(ZoneOffset.UTC);
        return new TradeFeatures(
                indicators.rsi.getValue(signalIndex).doubleValue(),
                distanceToBbLower,
                distanceToBbUpper,
                distanceToSupport,
                distanceToResistance,
                distanceToTrendEma,
                slope,
                atr,
                atrPercentile(indicators, signalIndex, 100),
                volumeRatio,
                signalTime.getHour(),
                signalTime.getDayOfWeek().getValue(),
                confluenceScore);
    }

    /** Rank of the ATR at {@code index} within the trailing {@code window} bars, in [0, 1]. */
    private static double atrPercentile(IndicatorBundle indicators, int index, int window) {
        int start = Math.max(indicators.series.getBeginIndex(), index - window + 1);
        double current = indicators.atr.getValue(index).doubleValue();
        int countBelow = 0;
        int total = 0;
        for (int cursor = start; cursor <= index; cursor++) {
            if (indicators.atr.getValue(cursor).doubleValue() <= current) {
                countBelow++;
            }
            total++;
        }
        return total == 0 ? 0.0 : (double) countBelow / total;
    }
}
