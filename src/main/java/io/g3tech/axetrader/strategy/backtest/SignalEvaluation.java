package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.EntrySignal;

public record SignalEvaluation(
        int entryIndex,
        EntrySignal signal,
        SignalOutcome outcome,
        VolatilityRegime volatilityRegime,
        TrendRegime trendRegime,
        int candlesToResolution,
        double maximumFavorableExcursion,
        double maximumAdverseExcursion,
        java.util.List<ForwardMovement> forwardMovements
) {

    public SignalEvaluation {
        forwardMovements = java.util.List.copyOf(forwardMovements);
    }

    public boolean isResolved() {
        return outcome != SignalOutcome.END_OF_DATA;
    }

    public boolean isCorrect() {
        return outcome == SignalOutcome.TARGET_HIT;
    }

    public boolean isLong() {
        return signal.direction() == Direction.LONG;
    }

    public boolean isShort() {
        return signal.direction() == Direction.SHORT;
    }
}
