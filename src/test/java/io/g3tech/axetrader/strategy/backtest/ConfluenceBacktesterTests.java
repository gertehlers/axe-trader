package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.ConfluenceSettings;
import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.IndicatorCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceBacktesterTests {

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

        assertThat(result.candlesProcessed()).isEqualTo(70);
        assertThat(result.warmupCandles()).isEqualTo(60);
        assertThat(result.signalsDetected()).isGreaterThan(0);
        assertThat(result.events()).allSatisfy(event -> assertThat(event.candleIndex()).isGreaterThanOrEqualTo(59));
        assertThat(result.events().getLast().signal().direction()).isEqualTo(Direction.LONG);
        assertThat(result.signalEvaluations()).isNotEmpty();
    }

    @Test
    void closesOpenTradeWhenTargetIsHit() {
        var detector = new ConfluenceEntryDetector(
                new IndicatorCalculator(),
                new ConfluenceSettings(60, 10, 8, 15, 0.65, 0.35, 1.5, 10)
        );
        var backtester = new ConfluenceBacktester(detector, new BacktestSettings(60, 1.0));
        var candles = trendingCandles(100, 0.35, Direction.LONG);
        candles.set(68, candle(68, 124.00, 124.30, 123.60, 124.05));
        candles.set(69, candle(69, 124.10, 126.20, 123.95, 126.00));
        candles.add(candle(70, 126.00, 130.00, 125.90, 129.00));

        var result = backtester.run(candles);

        assertThat(result.tradesClosed()).isGreaterThan(0);
        assertThat(result.trades().getFirst().exitReason()).isEqualTo(ExitReason.TARGET_HIT);
        assertThat(result.trades().getFirst().riskMultiple()).isEqualTo(1.0);
        assertThat(result.totalRiskMultiple()).isGreaterThanOrEqualTo(1.0);
        assertThat(result.correctSignals()).isGreaterThan(0);
        assertThat(result.accuracy()).isGreaterThan(0);
        assertThat(result.longAccuracy()).isGreaterThan(0);
        assertThat(result.signalEvaluations().getFirst().outcome()).isEqualTo(SignalOutcome.TARGET_HIT);
        assertThat(result.signalEvaluations().getFirst().candlesToResolution()).isGreaterThanOrEqualTo(1);
        assertThat(result.signalEvaluations().getFirst().maximumFavorableExcursion()).isGreaterThan(0);
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
