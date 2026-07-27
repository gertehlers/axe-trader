package io.g3tech.axetrader.backtest.discovery.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoricalSessionCalendarTest {

    @Test
    void recognizesARecurringMaintenanceGapAsTheSessionBoundary() {
        HistoricalSessionCalendar calendar = HistoricalSessionCalendar.fit(oneMinuteTimes(), 10);

        assertThat(calendar.minutesToClose(Instant.parse("2025-02-03T20:59:00Z"))).isEqualTo(60);
        assertThat(calendar.boundaryAfter(Instant.parse("2025-02-03T20:59:00Z")))
                .get()
                .extracting(SessionBoundary::finalExecutableBar)
                .isEqualTo(Instant.parse("2025-02-03T21:59:00Z"));
    }

    @Test
    void leavesAnIsolatedDataGapUnknown() {
        HistoricalSessionCalendar calendar = HistoricalSessionCalendar.fit(oneMinuteTimes(), 10);

        assertThat(calendar.boundaryAfter(Instant.parse("2025-02-04T18:00:00Z"))).isEmpty();
        assertThatThrownBy(() -> calendar.minutesToClose(Instant.parse("2025-02-04T18:00:00Z")))
                .isInstanceOf(UnknownSessionBoundaryException.class);
    }

    private static List<Instant> oneMinuteTimes() {
        List<Instant> times = new ArrayList<>();
        LocalDate firstMaintenanceMonday = LocalDate.parse("2024-12-02");
        for (int occurrence = 0; occurrence < 10; occurrence++) {
            addDayWithMaintenanceGap(times, firstMaintenanceMonday.plusWeeks(occurrence));
        }
        addDayWithIsolatedGap(times, LocalDate.parse("2025-02-04"));
        return times;
    }

    private static void addDayWithMaintenanceGap(List<Instant> times, LocalDate day) {
        addMinutes(times, day, 18 * 60, 21 * 60 + 59);
        addMinutes(times, day, 23 * 60, 23 * 60 + 10);
    }

    private static void addDayWithIsolatedGap(List<Instant> times, LocalDate day) {
        addMinutes(times, day, 18 * 60, 18 * 60 + 26);
        addMinutes(times, day, 19 * 60 + 4, 23 * 60 + 10);
    }

    private static void addMinutes(List<Instant> times, LocalDate day, int firstMinute, int lastMinute) {
        for (int minute = firstMinute; minute <= lastMinute; minute++) {
            times.add(day.atStartOfDay().plusMinutes(minute).toInstant(ZoneOffset.UTC));
        }
    }
}
