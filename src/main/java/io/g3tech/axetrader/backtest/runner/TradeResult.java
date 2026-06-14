package io.g3tech.axetrader.backtest.runner;

import java.time.ZonedDateTime;

public record TradeResult(
        ZonedDateTime entryTime,
        ZonedDateTime exitTime,
        Direction direction,
        double entryPrice,
        double exitPrice,
        double pnl,
        double rMultiple,
        VolatilityRegime regime,
        boolean isWin
) {
}
