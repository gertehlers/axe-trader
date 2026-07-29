package io.g3tech.axetrader.backtest.discovery.model;

import java.util.Objects;

/** Offline discovery join of backward-only features and a future-only outcome label. */
public record LabelledObservation(ObservableState state, ForwardPathLabel label) {

    public LabelledObservation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(label, "label");
    }
}
