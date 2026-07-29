package io.g3tech.axetrader.backtest.discovery.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Writes the stable compact JSON contract consumed by the discovery dashboard. */
public final class DiscoveryReportExporter {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void export(Path destination, DiscoveryReport report) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(report, "report");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("run", report.run());
        document.put("patterns", report.patterns());
        document.put("monthly_results", report.monthlyResults());
        document.put("directions", report.directions());
        document.put("examples", report.examples().stream().map(DiscoveryReportExporter::example).toList());
        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), document);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not export discovery report to " + destination, exception);
        }
    }

    private static Map<String, Object> example(DiscoveryReport.Example example) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", example.kind());
        result.put("observable_features", example.observableFeatures());
        result.put("pillar_transitions", example.pillarTransitions());
        result.put("oracle_result", example.oracleResult());
        result.put("executable_result", example.executableResult());
        result.put("rule_clauses", example.ruleClauses());
        result.put("chart_window", example.chartWindow());
        return result;
    }
}
