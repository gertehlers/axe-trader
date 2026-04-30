package io.g3tech.axetrader.strategy.backtest;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public record BacktestResult(
        int candlesProcessed,
        int warmupCandles,
        List<BacktestEvent> events,
        List<BacktestTrade> trades,
        List<SignalEvaluation> signalEvaluations
) {

    public BacktestResult {
        events = List.copyOf(events);
        trades = List.copyOf(trades);
        signalEvaluations = List.copyOf(signalEvaluations);
    }

    public int signalsDetected() {
        return events.size();
    }

    public int tradesClosed() {
        return trades.size();
    }

    public double totalRiskMultiple() {
        return trades.stream()
                .mapToDouble(BacktestTrade::riskMultiple)
                .sum();
    }

    public double winRate() {
        if (trades.isEmpty()) {
            return 0;
        }

        var winners = trades.stream()
                .filter(BacktestTrade::isWinner)
                .count();

        return (double) winners / trades.size();
    }

    public int resolvedSignals() {
        return (int) signalEvaluations.stream()
                .filter(SignalEvaluation::isResolved)
                .count();
    }

    public int correctSignals() {
        return (int) signalEvaluations.stream()
                .filter(SignalEvaluation::isCorrect)
                .count();
    }

    public int incorrectSignals() {
        return (int) signalEvaluations.stream()
                .filter(evaluation -> evaluation.outcome() == SignalOutcome.STOP_LOSS_HIT)
                .count();
    }

    public int unresolvedSignals() {
        return (int) signalEvaluations.stream()
                .filter(evaluation -> evaluation.outcome() == SignalOutcome.END_OF_DATA)
                .count();
    }

    public double accuracy() {
        return accuracy(SignalEvaluation::isResolved);
    }

    public double longAccuracy() {
        return accuracy(evaluation -> evaluation.isResolved() && evaluation.isLong());
    }

    public double shortAccuracy() {
        return accuracy(evaluation -> evaluation.isResolved() && evaluation.isShort());
    }

    public Map<String, AccuracySummary> accuracyByDirection() {
        return accuracyBy(evaluation -> evaluation.signal().direction().name());
    }

    public Map<String, AccuracySummary> accuracyByVolatilityRegime() {
        return accuracyBy(evaluation -> evaluation.volatilityRegime().name());
    }

    public Map<String, AccuracySummary> accuracyByTrendRegime() {
        return accuracyBy(evaluation -> evaluation.trendRegime().name());
    }

    public Map<String, AccuracySummary> accuracyByScore() {
        return accuracyBy(evaluation -> String.valueOf(evaluation.signal().score()));
    }

    public Map<String, AccuracySummary> accuracyByReason() {
        return signalEvaluations.stream()
                .flatMap(evaluation -> evaluation.signal().reasons().stream()
                        .map(reason -> Map.entry(reason, evaluation)))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.collectingAndThen(Collectors.toList(), AccuracySummary::from))
                ));
    }

    public Map<String, ForwardMovementSummary> forwardMovementByHorizon() {
        return signalEvaluations.stream()
                .flatMap(evaluation -> evaluation.forwardMovements().stream())
                .collect(Collectors.groupingBy(
                        movement -> String.valueOf(movement.horizonCandles()),
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                movements -> ForwardMovementSummary.from(movements.getFirst().horizonCandles(), movements)
                        )
                ));
    }

    private double accuracy(Predicate<SignalEvaluation> filter) {
        var matching = signalEvaluations.stream()
                .filter(filter)
                .toList();
        if (matching.isEmpty()) {
            return 0;
        }

        var correct = matching.stream()
                .filter(SignalEvaluation::isCorrect)
                .count();
        return (double) correct / matching.size();
    }

    private Map<String, AccuracySummary> accuracyBy(java.util.function.Function<SignalEvaluation, String> classifier) {
        return signalEvaluations.stream()
                .collect(Collectors.groupingBy(classifier, Collectors.collectingAndThen(Collectors.toList(), AccuracySummary::from)));
    }
}
