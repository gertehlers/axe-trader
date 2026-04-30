package io.g3tech.axetrader.strategy.backtest;

public record ForwardMovement(
        int horizonCandles,
        double favorableMove,
        double adverseMove,
        double closeMove
) {
}
