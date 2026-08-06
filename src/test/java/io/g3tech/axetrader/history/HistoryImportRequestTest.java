package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HistoryImportRequestTest {

    private static final Instant FROM = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2024-01-01T00:01:00Z");
    private static final Path STAGING = Path.of("build", "history-stage.sqlite");

    @Test
    void rejectsAnyResolutionOtherThanMinute() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                "US500", "HOUR", FROM, TO, STAGING, "capital"));
    }

    @Test
    void rejectsAnEmptyOrInvertedWindow() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                "US500", "MINUTE", FROM, FROM, STAGING, "capital"));
    }

    @Test
    void rejectsNonMinuteAlignedBoundsBeforePagingCanSplitThem() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                "US500", "MINUTE", FROM, Instant.parse("2024-01-01T00:01:30Z"), STAGING, "capital"))
                .withMessageContaining("whole UTC minutes");
    }

    @Test
    void rejectsMissingImportIdentityOrStagingPath() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                " ", "MINUTE", FROM, TO, STAGING, "capital"));
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                "US500", "MINUTE", FROM, TO, STAGING, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new HistoryImportRequest(
                "US500", "MINUTE", FROM, TO, null, "capital"));
    }
}
