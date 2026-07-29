package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.time.Instant;
import java.util.Map;

final class TestObservations {

    private TestObservations() {
    }

    static ObservableState state(Direction direction, int signalIndex, int entryIndex, double atr) {
        return new ObservableState(
                new ObservationId("US500", 5, Instant.parse("2026-01-05T00:05:00Z"), direction),
                signalIndex,
                entryIndex,
                atr,
                240,
                new FeatureVector(Map.of("observable", 1.0)));
    }
}
