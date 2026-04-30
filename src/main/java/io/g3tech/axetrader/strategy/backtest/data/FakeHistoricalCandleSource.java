package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.Direction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class FakeHistoricalCandleSource {

    public List<Candle> load(HistoricalPriceRequest request) {
        var candles = new ArrayList<Candle>();
        var start = request.from() == null ? Instant.parse("2026-04-30T00:00:00Z") : request.from();
        var seconds = resolutionSeconds(request.resolution());
        var direction = request.epic().toUpperCase().contains("SHORT") ? Direction.SHORT : Direction.LONG;
        var count = Math.max(request.max(), 80);

        for (int i = 0; i < count; i++) {
            var open = direction == Direction.LONG ? 100 + (i * 0.35) : 100 - (i * 0.35);
            var close = direction == Direction.LONG ? open + 0.22 : open - 0.22;
            var high = Math.max(open, close) + 0.25;
            var low = Math.min(open, close) - 0.25;

            candles.add(new Candle(
                    start.plusSeconds(seconds * i),
                    BigDecimal.valueOf(open),
                    BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low),
                    BigDecimal.valueOf(close),
                    1_000L
            ));
        }

        if (candles.size() >= 71) {
            candles.set(68, candle(start, seconds, 68, 124.00, 124.30, 123.60, 124.05));
            candles.set(69, candle(start, seconds, 69, 124.10, 126.20, 123.95, 126.00));
            candles.set(70, candle(start, seconds, 70, 126.00, 130.00, 125.90, 129.00));
        }

        return candles;
    }

    private Candle candle(Instant start, long seconds, int index, double open, double high, double low, double close) {
        return new Candle(
                start.plusSeconds(seconds * index),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                1_000L
        );
    }

    private long resolutionSeconds(String resolution) {
        return switch (resolution) {
            case "MINUTE" -> 60;
            case "MINUTE_5" -> 300;
            case "MINUTE_15" -> 900;
            case "MINUTE_30" -> 1_800;
            case "HOUR" -> 3_600;
            case "DAY" -> 86_400;
            default -> 300;
        };
    }
}
