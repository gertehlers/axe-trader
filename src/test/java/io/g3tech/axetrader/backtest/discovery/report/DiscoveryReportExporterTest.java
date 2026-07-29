package io.g3tech.axetrader.backtest.discovery.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryReportExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writes_the_compact_dashboard_contract_without_every_bar_observations() throws Exception {
        DiscoveryReport report = new DiscoveryReport(
                Map.of("run_key", "deterministic-run"),
                List.of(Map.of("id", "rule-1", "clauses", List.of(Map.of("feature", "rsi")))),
                List.of(Map.of("month", "2025-01", "trade_count", 12, "net_pnl", 4.0)),
                Map.of("LONG", Map.of("total_net", 4.0)),
                List.of(new DiscoveryReport.Example("best", Map.of("rsi", 30.0), Map.of("rsi_bb", 1.0),
                        Map.of("net_pnl", 5.0), Map.of("net_pnl", 3.0), List.of(Map.of("feature", "rsi")),
                        List.of(Map.of("time", "2025-01-01T00:00:00Z", "close", 100.0))))) ;

        Path destination = temporaryDirectory.resolve("nested/report.json");
        new DiscoveryReportExporter().export(destination, report);
        JsonNode json = new ObjectMapper().readTree(destination.toFile());

        assertThat(json.path("run").path("run_key").asText()).isEqualTo("deterministic-run");
        assertThat(json.path("patterns").isArray()).isTrue();
        assertThat(json.path("monthly_results").isArray()).isTrue();
        assertThat(json.path("examples").get(0).has("observable_features")).isTrue();
        assertThat(json.path("examples").get(0).has("pillar_transitions")).isTrue();
        assertThat(json.path("examples").get(0).has("oracle_result")).isTrue();
        assertThat(json.path("examples").get(0).has("executable_result")).isTrue();
        assertThat(json.path("examples").get(0).has("rule_clauses")).isTrue();
        assertThat(json.path("examples").get(0).has("chart_window")).isTrue();
        assertThat(json.has("observations")).isFalse();
    }
}
