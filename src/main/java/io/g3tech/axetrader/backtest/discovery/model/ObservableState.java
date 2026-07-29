package io.g3tech.axetrader.backtest.discovery.model;

import java.util.Objects;

public record ObservableState(
        ObservationId id,
        int signalIndex,
        int entryIndex,
        double entryAtr,
        int minutesToTradingClose,
        FeatureVector features) {

    public ObservableState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(features, "features");
        if (entryIndex != signalIndex + 1) {
            throw new IllegalArgumentException("entryIndex must be signalIndex + 1");
        }
        if (!Double.isFinite(entryAtr)) {
            throw new IllegalArgumentException("entryAtr must be finite");
        }
    }
}
