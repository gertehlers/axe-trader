package io.g3tech.axetrader.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class ConfluenceEntryDetector {

    private final IndicatorCalculator indicatorCalculator;
    private final ConfluenceSettings settings;

    @Autowired
    public ConfluenceEntryDetector(IndicatorCalculator indicatorCalculator) {
        this(indicatorCalculator, ConfluenceSettings.defaults());
    }

    public ConfluenceEntryDetector(IndicatorCalculator indicatorCalculator, ConfluenceSettings settings) {
        this.indicatorCalculator = indicatorCalculator;
        this.settings = settings;
    }

    public Optional<EntrySignal> detect(CandleWindow biasWindow, CandleWindow entryWindow) {
        if (biasWindow.size() < settings.minimumCandles() || entryWindow.size() < settings.minimumCandles()) {
            return Optional.empty();
        }

        var biasIndicators = indicatorCalculator.calculate(biasWindow);
        var entryIndicators = indicatorCalculator.calculate(entryWindow);
        var previousEntryIndicators = indicatorCalculator.calculate(entryWindow.withoutLatest());
        var bias = classifyBias(biasWindow.latest(), biasIndicators);

        var longCandidate = scoreLong(bias, entryWindow, entryIndicators, previousEntryIndicators);
        var shortCandidate = scoreShort(bias, entryWindow, entryIndicators, previousEntryIndicators);

        return Stream.of(longCandidate, shortCandidate)
                .flatMap(Optional::stream)
                .max(Comparator.comparingInt(EntrySignal::score));
    }

    public MarketBias classifyBias(Candle candle, IndicatorSnapshot indicators) {
        var longScore = 0;
        var shortScore = 0;

        if (candle.closeValue() > indicators.ema50()) {
            longScore += 2;
        }
        if (candle.closeValue() < indicators.ema50()) {
            shortScore += 2;
        }
        if (indicators.ema20() > indicators.ema50()) {
            longScore += 2;
        }
        if (indicators.ema20() < indicators.ema50()) {
            shortScore += 2;
        }
        if (indicators.ema20Slope() > 0) {
            longScore += 1;
        }
        if (indicators.ema20Slope() < 0) {
            shortScore += 1;
        }
        if (indicators.adx14() >= settings.minimumAdx() && indicators.plusDi14() > indicators.minusDi14()) {
            longScore += 2;
        }
        if (indicators.adx14() >= settings.minimumAdx() && indicators.minusDi14() > indicators.plusDi14()) {
            shortScore += 2;
        }

        if (longScore >= 5 && longScore > shortScore) {
            return MarketBias.STRENGTH;
        }

        if (shortScore >= 5 && shortScore > longScore) {
            return MarketBias.WEAKNESS;
        }

        return MarketBias.NEUTRAL;
    }

    private Optional<EntrySignal> scoreLong(
            MarketBias bias,
            CandleWindow window,
            IndicatorSnapshot indicators,
            IndicatorSnapshot previousIndicators
    ) {
        var latest = window.latest();
        var reasons = new ArrayList<String>();
        var score = 0;

        if (bias != MarketBias.STRENGTH) {
            return Optional.empty();
        }

        score += 3;
        reasons.add("higher timeframe strength bias");
        if (latest.closeValue() > indicators.ema20()) {
            score += 1;
            reasons.add("close above EMA20");
        }
        if (indicators.ema20() > indicators.ema50()) {
            score += 2;
            reasons.add("EMA20 above EMA50");
        }
        if (indicators.rsi14() > 50) {
            score += 1;
            reasons.add("RSI above 50");
        }
        if (indicators.rsi14() > previousIndicators.rsi14()) {
            score += 1;
            reasons.add("RSI rising");
        }
        if (indicators.adx14() >= settings.minimumAdx() && indicators.plusDi14() > indicators.minusDi14()) {
            score += 2;
            reasons.add("ADX confirms bullish directional strength");
        }
        if (latest.isBullish() && latest.closeLocation() >= settings.bullishCloseLocation()) {
            score += 2;
            reasons.add("bullish candle closed near high");
        }
        if (hasHigherHighAndHigherLow(window)) {
            score += 2;
            reasons.add("higher high and higher low structure");
        }
        if (breaksPriorHigh(window)) {
            score += 2;
            reasons.add("breakout above recent high");
        }
        if (pullbackHeldEma20(window, indicators, Direction.LONG)) {
            score += 2;
            reasons.add("pullback held EMA20");
        }

        if (isTooExtended(latest, indicators)) {
            return Optional.empty();
        }

        if (score < settings.minimumScore()) {
            return Optional.empty();
        }

        return Optional.of(new EntrySignal(
                Direction.LONG,
                latest.openedAt(),
                latest.close(),
                BigDecimal.valueOf(latest.closeValue() - (indicators.atr14() * settings.stopAtrMultiple())),
                score,
                bias,
                indicators,
                reasons
        ));
    }

    private Optional<EntrySignal> scoreShort(
            MarketBias bias,
            CandleWindow window,
            IndicatorSnapshot indicators,
            IndicatorSnapshot previousIndicators
    ) {
        var latest = window.latest();
        var reasons = new ArrayList<String>();
        var score = 0;

        if (bias != MarketBias.WEAKNESS) {
            return Optional.empty();
        }

        score += 3;
        reasons.add("higher timeframe weakness bias");
        if (latest.closeValue() < indicators.ema20()) {
            score += 1;
            reasons.add("close below EMA20");
        }
        if (indicators.ema20() < indicators.ema50()) {
            score += 2;
            reasons.add("EMA20 below EMA50");
        }
        if (indicators.rsi14() < 50) {
            score += 1;
            reasons.add("RSI below 50");
        }
        if (indicators.rsi14() < previousIndicators.rsi14()) {
            score += 1;
            reasons.add("RSI falling");
        }
        if (indicators.adx14() >= settings.minimumAdx() && indicators.minusDi14() > indicators.plusDi14()) {
            score += 2;
            reasons.add("ADX confirms bearish directional strength");
        }
        if (latest.isBearish() && latest.closeLocation() <= settings.bearishCloseLocation()) {
            score += 2;
            reasons.add("bearish candle closed near low");
        }
        if (hasLowerHighAndLowerLow(window)) {
            score += 2;
            reasons.add("lower high and lower low structure");
        }
        if (breaksPriorLow(window)) {
            score += 2;
            reasons.add("breakdown below recent low");
        }
        if (pullbackHeldEma20(window, indicators, Direction.SHORT)) {
            score += 2;
            reasons.add("pullback rejected EMA20");
        }

        if (isTooExtended(latest, indicators)) {
            return Optional.empty();
        }

        if (score < settings.minimumScore()) {
            return Optional.empty();
        }

        return Optional.of(new EntrySignal(
                Direction.SHORT,
                latest.openedAt(),
                latest.close(),
                BigDecimal.valueOf(latest.closeValue() + (indicators.atr14() * settings.stopAtrMultiple())),
                score,
                bias,
                indicators,
                reasons
        ));
    }

    private boolean hasHigherHighAndHigherLow(CandleWindow window) {
        var latest = window.latest();
        var previous = window.previous();

        return latest.highValue() > previous.highValue() && latest.lowValue() > previous.lowValue();
    }

    private boolean hasLowerHighAndLowerLow(CandleWindow window) {
        var latest = window.latest();
        var previous = window.previous();

        return latest.highValue() < previous.highValue() && latest.lowValue() < previous.lowValue();
    }

    private boolean breaksPriorHigh(CandleWindow window) {
        var candles = window.latest(settings.structureLookback() + 1);
        var latest = candles.getLast();
        var priorHigh = candles.subList(0, candles.size() - 1).stream()
                .mapToDouble(Candle::highValue)
                .max()
                .orElseThrow();

        return latest.closeValue() > priorHigh;
    }

    private boolean breaksPriorLow(CandleWindow window) {
        var candles = window.latest(settings.structureLookback() + 1);
        var latest = candles.getLast();
        var priorLow = candles.subList(0, candles.size() - 1).stream()
                .mapToDouble(Candle::lowValue)
                .min()
                .orElseThrow();

        return latest.closeValue() < priorLow;
    }

    private boolean pullbackHeldEma20(CandleWindow window, IndicatorSnapshot indicators, Direction direction) {
        var previous = window.previous();
        var latest = window.latest();

        if (direction == Direction.LONG) {
            return previous.lowValue() <= indicators.ema20()
                    && previous.closeValue() >= indicators.ema20()
                    && latest.closeValue() > previous.highValue();
        }

        return previous.highValue() >= indicators.ema20()
                && previous.closeValue() <= indicators.ema20()
                && latest.closeValue() < previous.lowValue();
    }

    private boolean isTooExtended(Candle candle, IndicatorSnapshot indicators) {
        if (indicators.atr14() == 0) {
            return true;
        }

        var distanceFromEma20 = Math.abs(candle.closeValue() - indicators.ema20());
        return distanceFromEma20 > indicators.atr14() * settings.maxAtrExtensionFromEma20();
    }
}
