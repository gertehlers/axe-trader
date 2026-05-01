package io.g3tech.axetrader.strategy.backtest;

import java.time.Instant;

public record BacktestPrice(
        String epic,
        String resolution,
        Instant snapshotTimeUtc,
        double openBid,
        double openAsk,
        double highBid,
        double highAsk,
        double lowBid,
        double lowAsk,
        double closeBid,
        double closeAsk,
        int lastTradedVolume
) {
}
