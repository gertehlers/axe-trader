package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryUpdateWindowTest {

    @Test
    void startsOneMinuteAfterTheCursorAndExcludesTheInProgressMinute() {
        Optional<HistoryUpdateWindow.Window> window = HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-02T22:52:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:37.412Z"));

        assertThat(window).contains(new HistoryUpdateWindow.Window(
                Instant.parse("2026-08-02T22:53:00Z"),
                Instant.parse("2026-08-06T09:14:00Z")));
    }

    @Test
    void reportsAlreadyCurrentWhenTheCursorIsTheLastCompletedMinute() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-06T09:13:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:00Z")))
                .isEmpty();
    }

    @Test
    void reportsAlreadyCurrentWhenTheCursorIsAheadOfTheLastCompletedMinute() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-06T09:20:00Z")),
                Optional.empty(),
                Instant.parse("2026-08-06T09:14:00Z")))
                .isEmpty();
    }

    @Test
    void seedsANewInstrumentFromTheConfiguredStart() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.empty(),
                Optional.of(Instant.parse("2024-01-01T00:00:00Z")),
                Instant.parse("2026-08-06T09:14:37Z")))
                .contains(new HistoryUpdateWindow.Window(
                        Instant.parse("2024-01-01T00:00:00Z"),
                        Instant.parse("2026-08-06T09:14:00Z")));
    }

    @Test
    void prefersTheCursorOverAConfiguredStart() {
        assertThat(HistoryUpdateWindow.resolve(
                Optional.of(Instant.parse("2026-08-02T22:52:00Z")),
                Optional.of(Instant.parse("2024-01-01T00:00:00Z")),
                Instant.parse("2026-08-06T09:14:00Z")))
                .map(HistoryUpdateWindow.Window::fromInclusive)
                .contains(Instant.parse("2026-08-02T22:53:00Z"));
    }

    @Test
    void failsClosedWhenThereIsNoCursorAndNoConfiguredStart() {
        assertThatIllegalStateException()
                .isThrownBy(() -> HistoryUpdateWindow.resolve(
                        Optional.empty(), Optional.empty(), Instant.parse("2026-08-06T09:14:00Z")))
                .withMessageContaining("explicit from");
    }

    @Test
    void rejectsAConfiguredStartThatIsNotOnAWholeMinute() {
        assertThatIllegalStateException()
                .isThrownBy(() -> HistoryUpdateWindow.resolve(
                        Optional.empty(),
                        Optional.of(Instant.parse("2024-01-01T00:00:30Z")),
                        Instant.parse("2026-08-06T09:14:00Z")))
                .withMessageContaining("whole UTC minute");
    }
}
