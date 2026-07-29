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
    }
}
