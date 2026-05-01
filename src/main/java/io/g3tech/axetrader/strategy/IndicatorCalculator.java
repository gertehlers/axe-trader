package io.g3tech.axetrader.strategy;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class IndicatorCalculator {

    private final IndicatorSettings settings;

    public IndicatorCalculator() {
        this(IndicatorSettings.defaults());
    }

    public IndicatorCalculator(IndicatorSettings settings) {
        this.settings = settings;
    }

    public IndicatorSnapshot calculate(CandleWindow window) {
        if (window.size() < settings.requiredCandles()) {
            throw new IllegalArgumentException("At least %d candles are required to calculate the strategy indicators".formatted(settings.requiredCandles()));
        }

        var candles = window.candles();
        var fastEma = ema(candles, settings.fastEmaPeriod());
        var previousFastEma = ema(candles.subList(0, candles.size() - 1), settings.fastEmaPeriod());
        var fastEmaDelta = fastEma - previousFastEma;
        var atr = atr(candles, settings.atrPeriod());
        var vwap = calculateVWAP(candles);

        var directional = directionalMovement(candles, settings.adxPeriod());

        return new IndicatorSnapshot(
                fastEma,
                ema(candles, settings.slowEmaPeriod()),
                fastEmaDelta,
                rsi(candles, settings.rsiPeriod()),
                atr,
                directional.adx(),
                directional.plusDi(),
                directional.minusDi(),
                new IndicatorSnapshot.IndicatorExtensions(
                        calculateVWAPMetrics(window.latest(), vwap, atr),
                        calculateCandleStrength(window.latest()),
                        calculateStructureBreak(candles, settings.structureLookbackPeriod()),
                        safeDivide(fastEmaDelta, atr)
                )
        );
    }

    double calculateVWAP(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Candles must not be empty");
        }

        if (hasAvailableVolume(candles)) {
            double cumulativePriceVolume = 0;
            double cumulativeVolume = 0;

            for (var candle : candles) {
                var volume = candle.volume().doubleValue();
                cumulativePriceVolume += typicalPrice(candle) * volume;
                cumulativeVolume += volume;
            }

            if (cumulativeVolume > 0) {
                return cumulativePriceVolume / cumulativeVolume;
            }
        }

        return candles.stream()
                .mapToDouble(this::typicalPrice)
                .average()
                .orElseThrow();
    }

    IndicatorSnapshot.CandleStrength calculateCandleStrength(Candle candle) {
        var range = candle.highValue() - candle.lowValue();
        return new IndicatorSnapshot.CandleStrength(
                safeDivide(Math.abs(candle.closeValue() - candle.openValue()), range),
                range == 0 ? 0.5 : safeDivide(candle.closeValue() - candle.lowValue(), range)
        );
    }

    IndicatorSnapshot.StructureBreak calculateStructureBreak(List<Candle> candles, int lookback) {
        if (lookback <= 0) {
            throw new IllegalArgumentException("Structure lookback must be positive");
        }
        if (candles == null || candles.size() <= lookback) {
            throw new IllegalArgumentException("Not enough candles to calculate structure lookback %d".formatted(lookback));
        }

        var currentClose = candles.getLast().closeValue();
        var structureCandles = candles.subList(candles.size() - lookback - 1, candles.size() - 1);
        var recentHigh = structureCandles.stream()
                .mapToDouble(Candle::highValue)
                .max()
                .orElseThrow();
        var recentLow = structureCandles.stream()
                .mapToDouble(Candle::lowValue)
                .min()
                .orElseThrow();

        return new IndicatorSnapshot.StructureBreak(
                recentHigh,
                recentLow,
                currentClose > recentHigh,
                currentClose < recentLow
        );
    }

    private IndicatorSnapshot.VWAPMetrics calculateVWAPMetrics(Candle latest, double vwap, double atr) {
        var distanceFromVwap = latest.closeValue() - vwap;
        return new IndicatorSnapshot.VWAPMetrics(vwap, distanceFromVwap, safeDivide(distanceFromVwap, atr));
    }

    private boolean hasAvailableVolume(List<Candle> candles) {
        return candles.stream().allMatch(candle -> candle.volume() != null && candle.volume() > 0);
    }

    private double typicalPrice(Candle candle) {
        return (candle.highValue() + candle.lowValue() + candle.closeValue()) / 3.0;
    }

    private double safeDivide(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private double ema(List<Candle> candles, int period) {
        if (candles.size() < period) {
            throw new IllegalArgumentException("Not enough candles to calculate EMA %d".formatted(period));
        }

        var multiplier = 2.0 / (period + 1);
        var ema = candles.subList(0, period).stream()
                .mapToDouble(Candle::closeValue)
                .average()
                .orElseThrow();

        for (int i = period; i < candles.size(); i++) {
            ema = ((candles.get(i).closeValue() - ema) * multiplier) + ema;
        }

        return ema;
    }

    private double rsi(List<Candle> candles, int period) {
        if (candles.size() <= period) {
            throw new IllegalArgumentException("Not enough candles to calculate RSI %d".formatted(period));
        }

        double averageGain = 0;
        double averageLoss = 0;

        for (int i = 1; i <= period; i++) {
            var change = candles.get(i).closeValue() - candles.get(i - 1).closeValue();
            averageGain += Math.max(change, 0);
            averageLoss += Math.max(-change, 0);
        }

        averageGain /= period;
        averageLoss /= period;

        for (int i = period + 1; i < candles.size(); i++) {
            var change = candles.get(i).closeValue() - candles.get(i - 1).closeValue();
            averageGain = ((averageGain * (period - 1)) + Math.max(change, 0)) / period;
            averageLoss = ((averageLoss * (period - 1)) + Math.max(-change, 0)) / period;
        }

        if (averageLoss == 0 && averageGain == 0) {
            return 50;
        }

        if (averageLoss == 0) {
            return 100;
        }

        var relativeStrength = averageGain / averageLoss;
        return 100 - (100 / (1 + relativeStrength));
    }

    private double atr(List<Candle> candles, int period) {
        return smoothedTrueRanges(candles, period).getLast() / period;
    }

    private List<Double> smoothedTrueRanges(List<Candle> candles, int period) {
        if (candles.size() <= period) {
            throw new IllegalArgumentException("Not enough candles to calculate ATR %d".formatted(period));
        }

        var trueRanges = new ArrayList<Double>();
        for (int i = 1; i < candles.size(); i++) {
            trueRanges.add(trueRange(candles.get(i), candles.get(i - 1)));
        }

        return wilderSmooth(trueRanges, period);
    }

    private double trueRange(Candle candle, Candle previous) {
        var highLow = candle.highValue() - candle.lowValue();
        var highPreviousClose = Math.abs(candle.highValue() - previous.closeValue());
        var lowPreviousClose = Math.abs(candle.lowValue() - previous.closeValue());

        return Math.max(highLow, Math.max(highPreviousClose, lowPreviousClose));
    }

    private DirectionalMovement directionalMovement(List<Candle> candles, int period) {
        if (candles.size() < (period * 2) + 1) {
            throw new IllegalArgumentException("Not enough candles to calculate ADX %d".formatted(period));
        }

        var plusMovement = new ArrayList<Double>();
        var minusMovement = new ArrayList<Double>();
        var trueRanges = new ArrayList<Double>();

        for (int i = 1; i < candles.size(); i++) {
            var current = candles.get(i);
            var previous = candles.get(i - 1);
            var upMove = current.highValue() - previous.highValue();
            var downMove = previous.lowValue() - current.lowValue();

            plusMovement.add(upMove > downMove && upMove > 0 ? upMove : 0);
            minusMovement.add(downMove > upMove && downMove > 0 ? downMove : 0);
            trueRanges.add(trueRange(current, previous));
        }

        var smoothedPlusMovement = wilderSmooth(plusMovement, period);
        var smoothedMinusMovement = wilderSmooth(minusMovement, period);
        var smoothedTrueRanges = wilderSmooth(trueRanges, period);

        var dxValues = new ArrayList<Double>();
        double latestPlusDi = 0;
        double latestMinusDi = 0;

        for (int i = 0; i < smoothedTrueRanges.size(); i++) {
            var trueRange = smoothedTrueRanges.get(i);
            var plusDi = trueRange == 0 ? 0 : 100 * (smoothedPlusMovement.get(i) / trueRange);
            var minusDi = trueRange == 0 ? 0 : 100 * (smoothedMinusMovement.get(i) / trueRange);
            var directionalSum = plusDi + minusDi;
            var dx = directionalSum == 0 ? 0 : 100 * (Math.abs(plusDi - minusDi) / directionalSum);

            latestPlusDi = plusDi;
            latestMinusDi = minusDi;
            dxValues.add(dx);
        }

        var adxValues = wilderSmooth(dxValues, period);
        return new DirectionalMovement(adxValues.getLast() / period, latestPlusDi, latestMinusDi);
    }

    private List<Double> wilderSmooth(List<Double> values, int period) {
        if (values.size() < period) {
            throw new IllegalArgumentException("Not enough values to smooth period %d".formatted(period));
        }

        var smoothed = new ArrayList<Double>();
        var first = values.subList(0, period).stream().mapToDouble(Double::doubleValue).sum();
        smoothed.add(first);

        var previous = first;
        for (int i = period; i < values.size(); i++) {
            previous = previous - (previous / period) + values.get(i);
            smoothed.add(previous);
        }

        return smoothed;
    }

    private record DirectionalMovement(double adx, double plusDi, double minusDi) {
    }
}
