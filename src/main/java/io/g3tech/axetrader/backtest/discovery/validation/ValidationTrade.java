package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.runner.TieredExitEngine;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One non-overlapping, side-aware executable validation trade. */
public record ValidationTrade(
        String candidateId,
        Direction direction,
        int entryIndex,
        int exitIndex,
        Instant entryTime,
        Instant exitTime,
        double entryPrice,
        double exitPrice,
        double netPnl,
        ExitReason exitReason,
        List<TieredExitEngine.TierFill> tierFills,
        double captureRatio) {

    public ValidationTrade {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(entryTime, "entryTime");
        Objects.requireNonNull(exitTime, "exitTime");
        Objects.requireNonNull(exitReason, "exitReason");
        tierFills = List.copyOf(Objects.requireNonNull(tierFills, "tierFills"));
        if (candidateId.isBlank() || entryIndex < 0 || exitIndex < entryIndex || exitTime.isBefore(entryTime)
                || !Double.isFinite(entryPrice) || !Double.isFinite(exitPrice) || !Double.isFinite(netPnl)
                || !Double.isFinite(captureRatio)) {
            throw new IllegalArgumentException("invalid validation trade");
        }
    }
}
