package io.g3tech.axetrader.history;

import java.time.Duration;

/** Capital.com history resolutions accepted by both fetching and staging audits. */
public enum CapitalHistoryResolution {
    MINUTE(Duration.ofMinutes(1)),
    MINUTE_5(Duration.ofMinutes(5)),
    MINUTE_15(Duration.ofMinutes(15)),
    MINUTE_30(Duration.ofMinutes(30)),
    HOUR(Duration.ofHours(1)),
    HOUR_4(Duration.ofHours(4)),
    DAY(Duration.ofDays(1)),
    WEEK(Duration.ofDays(7));

    private final Duration duration;

    CapitalHistoryResolution(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    public static CapitalHistoryResolution requireSupported(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("unsupported Capital history resolution: " + value, exception);
        }
    }
}
