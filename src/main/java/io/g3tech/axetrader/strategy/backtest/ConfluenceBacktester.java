package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.CandleWindow;
import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;

import java.util.ArrayList;
import java.util.List;

public class ConfluenceBacktester {

    private final ConfluenceEntryDetector entryDetector;
    private final int minimumCandles;

    public ConfluenceBacktester(ConfluenceEntryDetector entryDetector, int minimumCandles) {
        if (minimumCandles <= 1) {
            throw new IllegalArgumentException("Minimum candles must be greater than one");
        }

        this.entryDetector = entryDetector;
        this.minimumCandles = minimumCandles;
    }

    public BacktestResult run(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Backtest candles must not be empty");
        }

        var replay = new ArrayList<Candle>();
        var events = new ArrayList<BacktestEvent>();

        for (int i = 0; i < candles.size(); i++) {
            var candleIndex = i;
            var candle = candles.get(i);
            replay.add(candle);

            if (replay.size() < minimumCandles) {
                continue;
            }

            var window = new CandleWindow(replay);
            entryDetector.detect(window, window)
                    .map(signal -> new BacktestEvent(candleIndex, candle, signal))
                    .ifPresent(events::add);
        }

        return new BacktestResult(candles.size(), minimumCandles, events);
    }
}
