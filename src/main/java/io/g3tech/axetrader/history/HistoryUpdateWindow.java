package io.g3tech.axetrader.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

public final class HistoryUpdateWindow {

    private HistoryUpdateWindow() {
    }

    /**
     * Resolves the half-open window an incremental update should import.
     *
     * @return the window, or empty when the instrument is already current
     */
    public static Optional<Window> resolve(Optional<Instant> cursor, Optional<Instant> configuredFrom, Instant now) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(configuredFrom, "configuredFrom");
        Objects.requireNonNull(now, "now");

        Instant toExclusive = now.truncatedTo(ChronoUnit.MINUTES);
        Instant fromInclusive = cursor
                .map(minute -> minute.plus(1, ChronoUnit.MINUTES))
                .or(() -> configuredFrom)
                .orElseThrow(() -> new IllegalStateException(
                        "Instrument has no stored history; seeding it requires an explicit from"));
        requireWholeMinute(fromInclusive);

        return fromInclusive.isBefore(toExclusive)
                ? Optional.of(new Window(fromInclusive, toExclusive))
                : Optional.empty();
    }

    private static void requireWholeMinute(Instant instant) {
        if (instant.getNano() != 0 || Math.floorMod(instant.getEpochSecond(), 60) != 0) {
            throw new IllegalStateException("Import bounds must fall on a whole UTC minute: " + instant);
        }
    }

    public record Window(Instant fromInclusive, Instant toExclusive) {

        public Window {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("fromInclusive must be before toExclusive");
            }
        }
    }
}
