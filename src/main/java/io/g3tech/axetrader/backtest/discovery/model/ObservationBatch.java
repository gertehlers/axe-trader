package io.g3tech.axetrader.backtest.discovery.model;

import java.util.List;
import java.util.Objects;

public record ObservationBatch(
        List<ObservableState> states,
        List<ObservationExclusion> exclusions) {

    public ObservationBatch {
        states = List.copyOf(Objects.requireNonNull(states, "states"));
        exclusions = List.copyOf(Objects.requireNonNull(exclusions, "exclusions"));
    }
}
