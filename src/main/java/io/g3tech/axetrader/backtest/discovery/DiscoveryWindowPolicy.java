package io.g3tech.axetrader.backtest.discovery;

import java.time.Instant;
import java.util.Objects;

public final class DiscoveryWindowPolicy {

    public static final Instant OOS_FROM = Instant.parse("2026-01-01T00:00:00Z");
    public static final Instant OOS_TO = Instant.parse("2026-05-02T00:00:00Z");

    public void requireDevelopmentWindow(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isBefore(OOS_TO) && OOS_FROM.isBefore(to)) {
            throw new IllegalArgumentException("Development window overlaps protected OOS period");
        }
        // Everything from OOS_TO onward is held back as an untouched reserve for a future final
        // validation. The overlap check above guards only the band and lets tail windows through.
        if (to.isAfter(OOS_FROM)) {
            throw new IllegalArgumentException(
                    "Development window reaches into the reserved tail; it must end at or before " + OOS_FROM);
        }
    }
}
