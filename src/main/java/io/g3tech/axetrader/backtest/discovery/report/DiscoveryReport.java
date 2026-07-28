package io.g3tech.axetrader.backtest.discovery.report;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compact export model: deliberately no every-bar observation collection. */
public record DiscoveryReport(
        Map<String, Object> run,
        List<Map<String, Object>> patterns,
        List<Map<String, Object>> monthlyResults,
        Map<String, Map<String, Object>> directions,
        List<Example> examples) {

    public DiscoveryReport {
        run = Map.copyOf(Objects.requireNonNull(run, "run"));
        patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
        monthlyResults = List.copyOf(Objects.requireNonNull(monthlyResults, "monthlyResults"));
        directions = Map.copyOf(Objects.requireNonNull(directions, "directions"));
        examples = List.copyOf(Objects.requireNonNull(examples, "examples"));
    }

    public record Example(
            String kind,
            Map<String, Double> observableFeatures,
            Map<String, Double> pillarTransitions,
            Map<String, Object> oracleResult,
            Map<String, Object> executableResult,
            List<Map<String, Object>> ruleClauses,
            List<Map<String, Object>> chartWindow) {
        public Example {
            Objects.requireNonNull(kind, "kind");
            observableFeatures = Map.copyOf(Objects.requireNonNull(observableFeatures, "observableFeatures"));
            pillarTransitions = Map.copyOf(Objects.requireNonNull(pillarTransitions, "pillarTransitions"));
            oracleResult = Map.copyOf(Objects.requireNonNull(oracleResult, "oracleResult"));
            executableResult = Map.copyOf(Objects.requireNonNull(executableResult, "executableResult"));
            ruleClauses = List.copyOf(Objects.requireNonNull(ruleClauses, "ruleClauses"));
            chartWindow = List.copyOf(Objects.requireNonNull(chartWindow, "chartWindow"));
            if (kind.isBlank()) {
                throw new IllegalArgumentException("example kind must not be blank");
            }
        }
    }
}
