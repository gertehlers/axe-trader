package io.g3tech.axetrader.strategy.backtest;

import java.util.List;

public record AccuracySummary(
        int totalSignals,
        int resolvedSignals,
        int correctSignals,
        int incorrectSignals,
        int unresolvedSignals,
        double accuracy
) {

    public static AccuracySummary from(List<SignalEvaluation> evaluations) {
        var resolved = evaluations.stream()
                .filter(SignalEvaluation::isResolved)
                .toList();
        var correct = (int) resolved.stream()
                .filter(SignalEvaluation::isCorrect)
                .count();
        var incorrect = (int) resolved.stream()
                .filter(evaluation -> evaluation.outcome() == SignalOutcome.STOP_LOSS_HIT)
                .count();
        var unresolved = (int) evaluations.stream()
                .filter(evaluation -> evaluation.outcome() == SignalOutcome.END_OF_DATA)
                .count();
        var accuracy = resolved.isEmpty() ? 0 : (double) correct / resolved.size();

        return new AccuracySummary(
                evaluations.size(),
                resolved.size(),
                correct,
                incorrect,
                unresolved,
                accuracy
        );
    }
}
