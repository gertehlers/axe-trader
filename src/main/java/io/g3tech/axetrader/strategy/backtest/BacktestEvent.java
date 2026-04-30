package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.EntrySignal;

public record BacktestEvent(
        int candleIndex,
        Candle candle,
        EntrySignal signal
) {
}
