package io.g3tech.axetrader.strategy.backtest;

import java.util.List;

public record ForwardMovementSummary(
        int horizonCandles,
        double averageFavorableMove,
        double averageAdverseMove,
        double averageCloseMove
) {

    public static ForwardMovementSummary from(int horizonCandles, List<ForwardMovement> movements) {
        if (movements.isEmpty()) {
            return new ForwardMovementSummary(horizonCandles, 0, 0, 0);
        }

        return new ForwardMovementSummary(
                horizonCandles,
                movements.stream().mapToDouble(ForwardMovement::favorableMove).average().orElse(0),
                movements.stream().mapToDouble(ForwardMovement::adverseMove).average().orElse(0),
                movements.stream().mapToDouble(ForwardMovement::closeMove).average().orElse(0)
        );
    }
}
