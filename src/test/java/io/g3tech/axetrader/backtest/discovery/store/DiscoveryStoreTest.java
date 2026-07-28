package io.g3tech.axetrader.backtest.discovery.store;

import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.discovery.model.PathPoint;
import io.g3tech.axetrader.backtest.runner.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsObservableFeaturesAndForwardLabelsInSeparateTables() throws Exception {
        Path database = tempDir.resolve("discovery.sqlite");
        ObservableState state = state();
        ForwardPathLabel label = label();

        try (DiscoveryStore store = DiscoveryStore.open(database)) {
            long runId = store.beginRun(run());
            store.saveObservation(runId, state);
            store.saveLabel(runId, state.id(), label);

            assertThat(store.findObservableState(runId, state.id())).contains(state);
            assertThat(store.findForwardPathLabel(runId, state.id())).contains(label);
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var observation = connection.prepareStatement(
                    "SELECT features_json FROM observation");
                 var rows = observation.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("features_json")).contains("atr").contains("rsi");
            }
            try (var columns = connection.createStatement().executeQuery("PRAGMA table_info(observation)")) {
                assertThat(columnNames(columns)).contains("features_json").doesNotContain("mfe_points");
            }
            try (var columns = connection.createStatement().executeQuery("PRAGMA table_info(forward_label)")) {
                assertThat(columnNames(columns)).contains("mfe_points").doesNotContain("features_json");
            }
        }
    }

    @Test
    void rejectsDuplicateObservationIdentityWithinOneRun() {
        try (DiscoveryStore store = DiscoveryStore.open(tempDir.resolve("discovery.sqlite"))) {
            long runId = store.beginRun(run());
            store.saveObservation(runId, state());

            assertThatThrownBy(() -> store.saveObservation(runId, state()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate observation");
        }
    }

    @Test
    void derivesRunKeyFromAllCanonicalRunIdentityFields() {
        DiscoveryRun first = run();
        DiscoveryRun sameInputs = run();
        DiscoveryRun changedScoreVersion = new DiscoveryRun(
                "input-sha", "config-sha", "features-v1", "score-v2", "abc123", false,
                "US500", 5, Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z"));

        assertThat(first.runKey()).isEqualTo(sameInputs.runKey());
        assertThat(changedScoreVersion.runKey()).isNotEqualTo(first.runKey());
    }

    @Test
    void recordsEachSpentWindowOnlyOnce() {
        try (DiscoveryStore store = DiscoveryStore.open(tempDir.resolve("discovery.sqlite"))) {
            long firstRun = store.beginRun(run());
            long secondRun = store.beginRun(new DiscoveryRun(
                    "input-sha", "config-sha", "features-v1", "score-v1", "def456", false,
                    "US500", 5, Instant.parse("2025-02-01T00:00:00Z"), Instant.parse("2025-03-01T00:00:00Z")));
            Instant from = Instant.parse("2026-01-01T00:00:00Z");
            Instant to = Instant.parse("2026-05-02T00:00:00Z");

            store.appendWindowSpent(firstRun, from, to, "candidate-1");

            assertThatThrownBy(() -> store.appendWindowSpent(secondRun, from, to, "candidate-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already spent");
        }
    }

    private static List<String> columnNames(java.sql.ResultSet columns) throws Exception {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        while (columns.next()) {
            names.add(columns.getString("name"));
        }
        return names;
    }

    private static DiscoveryRun run() {
        return new DiscoveryRun(
                "input-sha", "config-sha", "features-v1", "score-v1", "abc123", false,
                "US500", 5, Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z"));
    }

    private static ObservableState state() {
        return new ObservableState(
                new ObservationId("US500", 5, Instant.parse("2025-01-05T00:05:00Z"), Direction.LONG),
                10, 11, 2.5, 30, new FeatureVector(Map.of("atr", 2.5, "rsi", 55.0)));
    }

    private static ForwardPathLabel label() {
        return new ForwardPathLabel(
                LabelStatus.COMPLETE_48_BARS,
                5000.5,
                Instant.parse("2025-01-05T00:10:00Z"),
                List.of(new PathPoint(11, Instant.parse("2025-01-05T00:10:00Z"), 5000, 5003, 4999, 5002)),
                5.0, 2.0, -1.0, -0.4, false, ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE,
                Map.of(5, 2.0), Map.of(5, 0.8), 0.75, 1, 1);
    }
}
