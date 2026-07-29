package io.g3tech.axetrader.backtest.discovery.model;

import io.g3tech.axetrader.backtest.runner.Direction;

import java.time.Instant;
import java.util.Objects;

public record ObservationId(
        String instrument,
        int timeframeMinutes,
        Instant signalTime,
        Direction direction) {

    public ObservationId {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(signalTime, "signalTime");
        Objects.requireNonNull(direction, "direction");
        if (instrument.isBlank()) {
            throw new IllegalArgumentException("instrument must not be blank");
        }
        if (timeframeMinutes <= 0) {
            throw new IllegalArgumentException("timeframeMinutes must be positive");
        }
    }
}
