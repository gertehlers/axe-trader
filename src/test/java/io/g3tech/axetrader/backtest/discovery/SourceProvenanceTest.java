package io.g3tech.axetrader.backtest.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceProvenanceTest {

    @Test
    void treatsAModifiedSourceFileAsBlocking() {
        assertThat(SourceProvenance.blockingChanges(List.of(
                " M src/main/java/io/g3tech/axetrader/AxeTraderApplication.java")))
                .containsExactly("src/main/java/io/g3tech/axetrader/AxeTraderApplication.java");
    }

    @Test
    void treatsAModifiedApplicationYamlAsBlocking() {
        assertThat(SourceProvenance.blockingChanges(List.of(" M src/main/resources/application.yaml")))
                .containsExactly("src/main/resources/application.yaml");
    }

    @Test
    void ignoresChartOutputBecauseTheTestSuiteRewritesIt() {
        assertThat(SourceProvenance.blockingChanges(List.of(
                " M output/charts/chart.html",
                " M output/charts/runner-results.html")))
                .isEmpty();
    }

    @Test
    void ignoresUntrackedFilesOutsideSource() {
        assertThat(SourceProvenance.blockingChanges(List.of("?? data/some-import.log"))).isEmpty();
    }

    @Test
    void reportsAnUntrackedSourceFileBecauseItChangesWhatCompiles() {
        assertThat(SourceProvenance.blockingChanges(List.of("?? src/main/java/io/g3tech/axetrader/New.java")))
                .containsExactly("src/main/java/io/g3tech/axetrader/New.java");
    }

    @Test
    void readsTheDestinationOfARenameRatherThanTheArrow() {
        assertThat(SourceProvenance.blockingChanges(List.of("R  src/main/java/Old.java -> src/main/java/New.java")))
                .containsExactly("src/main/java/New.java");
    }

    @Test
    void treatsACleanTreeAsUnblocked() {
        assertThat(SourceProvenance.blockingChanges(List.of())).isEmpty();
    }
}
