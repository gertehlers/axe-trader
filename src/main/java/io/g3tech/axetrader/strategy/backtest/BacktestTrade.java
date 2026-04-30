package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.EntrySignal;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestTrade(
        Direction direction,
        int entryIndex,
        Instant entryTime,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        int exitIndex,
        Instant exitTime,
        BigDecimal exitPrice,
        ExitReason exitReason,
        double riskMultiple,
        EntrySignal signal
) {

    public boolean isWinner() {
        return riskMultiple > 0;
    }
}
