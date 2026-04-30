package io.g3tech.axetrader.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceEntryDetectorTests {

    private final ConfluenceEntryDetector detector = new ConfluenceEntryDetector(
            new IndicatorCalculator(),
            new ConfluenceSettings(60, 10, 8, 15, 0.65, 0.35, 1.5, 10)
    );

    @Test
    void detectsLongEntryWhenStrengthConfluenceIsPresent() {
        var candles = trendingCandles(100, 0.35, Direction.LONG);
        candles.set(68, candle(68, 124.00, 124.30, 123.60, 124.05));
        candles.set(69, candle(69, 124.10, 126.20, 123.95, 126.00));

        var signal = detector.detect(new CandleWindow(candles), new CandleWindow(candles));

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.LONG);
        assertThat(signal.get().bias()).isEqualTo(MarketBias.STRENGTH);
        assertThat(signal.get().score()).isGreaterThanOrEqualTo(8);
        assertThat(signal.get().reasons()).contains("higher timeframe strength bias");
    }

    @Test
    void detectsShortEntryWhenWeaknessConfluenceIsPresent() {
        var candles = trendingCandles(100, 0.35, Direction.SHORT);
        candles.set(68, candle(68, 76.00, 76.40, 75.70, 75.95));
        candles.set(69, candle(69, 75.90, 76.05, 73.80, 74.00));

        var signal = detector.detect(new CandleWindow(candles), new CandleWindow(candles));

        assertThat(signal).isPresent();
        assertThat(signal.get().direction()).isEqualTo(Direction.SHORT);
        assertThat(signal.get().bias()).isEqualTo(MarketBias.WEAKNESS);
        assertThat(signal.get().score()).isGreaterThanOrEqualTo(8);
        assertThat(signal.get().reasons()).contains("higher timeframe weakness bias");
    }

    @Test
    void ignoresFlatMarketWithoutDirectionalBias() {
        var candles = new ArrayList<Candle>();
        for (int i = 0; i < 70; i++) {
            var base = 100 + Math.sin(i) * 0.2;
            candles.add(candle(i, base, base + 0.35, base - 0.35, base + 0.05));
        }

        var signal = detector.detect(new CandleWindow(candles), new CandleWindow(candles));

        assertThat(signal).isEmpty();
    }

    private List<Candle> trendingCandles(double start, double step, Direction direction) {
        var candles = new ArrayList<Candle>();
        for (int i = 0; i < 70; i++) {
            var open = direction == Direction.LONG ? start + (i * step) : start - (i * step);
            var close = direction == Direction.LONG ? open + 0.22 : open - 0.22;
            var high = Math.max(open, close) + 0.25;
            var low = Math.min(open, close) - 0.25;
            candles.add(candle(i, open, high, low, close));
        }

        return candles;
    }

    private Candle candle(int index, double open, double high, double low, double close) {
        return new Candle(
                Instant.parse("2026-04-30T00:00:00Z").plusSeconds(index * 300L),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                1_000L
        );
    }
}
