package io.g3tech.axetrader.strategy.backtest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

class ProfileReporter {

    ProfileReport build(BacktestResult result) {
        var byReason = result.accuracyByReason();
        return new ProfileReport(
                ranked(byReason, Comparator.<Map.Entry<String, AccuracySummary>>comparingDouble(entry -> entry.getValue().accuracy()).reversed()),
                ranked(byReason, Comparator.comparingDouble(entry -> entry.getValue().accuracy()))
        );
    }

    private List<String> ranked(
            Map<String, AccuracySummary> summaries,
            Comparator<Map.Entry<String, AccuracySummary>> comparator
    ) {
        return summaries.entrySet().stream()
                .filter(entry -> entry.getValue().resolvedSignals() >= 2)
                .sorted(comparator)
                .limit(5)
                .map(entry -> "%s -> accuracy=%.3f resolved=%d total=%d".formatted(
                        entry.getKey(),
                        entry.getValue().accuracy(),
                        entry.getValue().resolvedSignals(),
                        entry.getValue().totalSignals()
                ))
                .toList();
    }
}
