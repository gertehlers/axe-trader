package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.runner.TieredExitEngine;

import java.util.List;

/** An executable-policy result plus its hindsight capture diagnostic. */
public record ExitEvaluation(
        int entryIndex,
        double entryPrice,
        int exitIndex,
        double exitPrice,
        double netPnl,
        ExitReason exitReason,
        List<TieredExitEngine.TierFill> tierFills,
        double captureRatio,
        List<RuleClause> firedInvalidations) {

    public ExitEvaluation {
        tierFills = List.copyOf(tierFills);
        firedInvalidations = List.copyOf(firedInvalidations);
    }
}
