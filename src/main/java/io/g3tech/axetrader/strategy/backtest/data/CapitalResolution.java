package io.g3tech.axetrader.strategy.backtest.data;

import java.time.Duration;

enum CapitalResolution {
    MINUTE(Duration.ofMinutes(1)),
    MINUTE_5(Duration.ofMinutes(5)),
    MINUTE_15(Duration.ofMinutes(15)),
    MINUTE_30(Duration.ofMinutes(30)),
    HOUR(Duration.ofHours(1)),
    HOUR_4(Duration.ofHours(4)),
    DAY(Duration.ofDays(1)),
    WEEK(Duration.ofDays(7));

    private final Duration duration;

    CapitalResolution(Duration duration) {
        this.duration = duration;
    }

    Duration duration() {
        return duration;
    }

    static CapitalResolution from(String value) {
        return CapitalResolution.valueOf(value.trim().replace('-', '_').toUpperCase());
    }
}
