package io.g3tech.axetrader.strategy;

import java.util.List;

public record CandleWindow(List<Candle> candles) {

    public CandleWindow {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Candle window must not be empty");
        }

        candles = List.copyOf(candles);
    }

    public int size() {
        return candles.size();
    }

    public Candle latest() {
        return candles.getLast();
    }

    public Candle previous() {
        if (candles.size() < 2) {
            throw new IllegalStateException("Candle window needs at least two candles");
        }

        return candles.get(candles.size() - 2);
    }

    public CandleWindow withoutLatest() {
        if (candles.size() < 2) {
            throw new IllegalStateException("Candle window needs at least two candles");
        }

        return new CandleWindow(candles.subList(0, candles.size() - 1));
    }

    public List<Candle> latest(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        if (count >= candles.size()) {
            return candles;
        }

        return candles.subList(candles.size() - count, candles.size());
    }
}
