package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryImportPropertiesTest {

    private static HistoryImportProperties properties(String mode) {
        return new HistoryImportProperties(true, mode, "US500", "MINUTE",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"),
                null, null, null, null);
    }

    @Test
    void recognisesTheUpdateReportAndArchiveModes() {
        assertThat(properties("update").requiredMode()).isEqualTo(HistoryImportProperties.Mode.UPDATE);
        assertThat(properties("report").requiredMode()).isEqualTo(HistoryImportProperties.Mode.REPORT);
        assertThat(properties("archive").requiredMode()).isEqualTo(HistoryImportProperties.Mode.ARCHIVE);
    }

    @Test
    void defaultsTheStagingDirectory() {
        assertThat(properties("update").stagingDirectory()).isEqualTo(Path.of("data", ".staging"));
    }

    @Test
    void stillRequiresAStagingDatabaseForTheRebuildModes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> properties("stage").requiredStagingDatabase())
                .withMessageContaining("staging-database");
    }
}
