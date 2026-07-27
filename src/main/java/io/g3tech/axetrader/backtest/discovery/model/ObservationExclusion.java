package io.g3tech.axetrader.backtest.discovery.model;

import java.util.Objects;

public record ObservationExclusion(
        ObservationId id,
        int signalIndex,
        Reason reason,
        String detail) {

    public ObservationExclusion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
    }

    public enum Reason {
        INDICATOR_WARMUP,
        UNKNOWN_SESSION,
        NO_EXECUTABLE_NEXT_BAR,
        NON_FINITE_FEATURE
    }
}
