package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThat;

class HistoryImportRequestTest {

    @Test
    void discoveryRequestRejectsProtectedOosBeforeItCanBeUsed() {
        assertThatIllegalArgumentException().isThrownBy(() -> request(
                "US500", "MINUTE", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", true));
    }

    @Test
    void requestRequiresAValidHalfOpenWindowAndStagingPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> request(
                "", "MINUTE", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z", false));
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                "US500", "MINUTE", Instant.parse("2025-01-02T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"), null, "capital", false));
    }

    @Test
    void omittedSourceDefaultsToCapital() {
        HistoryImportRequest request = new HistoryImportRequest(
                "US500", "MINUTE", Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"), Path.of("target/history.sqlite"), false);

        assertThat(request.source()).isEqualTo("capital");
    }

    private static HistoryImportRequest request(
            String epic, String resolution, String from, String to, boolean discoveryWindow) {
        return new HistoryImportRequest(epic, resolution, Instant.parse(from), Instant.parse(to),
                Path.of("target/history.sqlite"), "capital", discoveryWindow);
    }
}
