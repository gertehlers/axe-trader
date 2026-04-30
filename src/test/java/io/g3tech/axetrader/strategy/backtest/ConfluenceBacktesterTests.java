package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.ConfluenceSettings;
import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.IndicatorCalculator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceBacktesterTests {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceBacktesterTests.class);

    @Test
    void replaysCandlesAsLiveDataAndRecordsDetectedSignals() {
        var detector = new ConfluenceEntryDetector(
                new IndicatorCalculator(),
                new ConfluenceSettings(60, 10, 8, 15, 0.65, 0.35, 1.5, 10)
        );
        var backtester = new ConfluenceBacktester(detector, 60);
        var candles = trendingCandles(100, 0.35, Direction.LONG);
        candles.set(68, candle(68, 124.00, 124.30, 123.60, 124.05));
        candles.set(69, candle(69, 124.10, 126.20, 123.95, 126.00));

        var result = backtester.run(candles);

        log.info("Result: {}", result);

        assertThat(result.candlesProcessed()).isEqualTo(70);
        assertThat(result.warmupCandles()).isEqualTo(60);
        assertThat(result.signalsDetected()).isGreaterThan(0);
        assertThat(result.events()).allSatisfy(event -> assertThat(event.candleIndex()).isGreaterThanOrEqualTo(59));
        assertThat(result.events().getLast().signal().direction()).isEqualTo(Direction.LONG);
    }

    private List<Candle> trendingCandles(double start, double step, Direction direction) {
        var candles = new ArrayList<Candle>();
        for (int i = 0; i < 70; i++) {
            var open = direction == Direction.LONG ? start + (i * step) : start - (i * step);
            var close = direction == Direction.LONG ? open + 0.22 : open - 0.22;
            var high = Math.max(open, close) + 0.25;
            var low = Math.min(open, close) - 0.25;

            log.info("Candle {}: open={}, high={}, low={}, close={}", i, open, high, low, close);

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
