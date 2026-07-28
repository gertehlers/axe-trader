package io.g3tech.axetrader.backtest.discovery.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Future-only outcome data for offline discovery. It is deliberately separate from
 * {@link ObservableState}, which remains the no-leakage feature boundary.
 */
public record ForwardPathLabel(
        LabelStatus status,
        double entryPrice,
        Instant entryTime,
        List<PathPoint> exitPath,
        double mfePoints,
        double mfeAtr,
        double maePoints,
        double maeAtr,
        boolean maeBeforeMfe,
        ExcursionOrder excursionOrder,
        Map<Integer, Double> horizonReturnsPoints,
        Map<Integer, Double> horizonReturnsAtr,
        double directionalEfficiency,
        int timeToMfeBars,
        int timeToMaeBars) {

    public ForwardPathLabel {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(excursionOrder, "excursionOrder");
        exitPath = List.copyOf(Objects.requireNonNull(exitPath, "exitPath"));
        horizonReturnsPoints = orderedCopy(horizonReturnsPoints, "horizonReturnsPoints");
        horizonReturnsAtr = orderedCopy(horizonReturnsAtr, "horizonReturnsAtr");
        if (timeToMfeBars < 0 || timeToMaeBars < 0) {
            throw new IllegalArgumentException("event times must be non-negative");
        }
        if (status == LabelStatus.NO_EXECUTABLE_NEXT_BAR) {
            if (entryTime != null || !Double.isNaN(entryPrice) || !exitPath.isEmpty()) {
                throw new IllegalArgumentException("unexecutable labels cannot contain an entry or exit path");
            }
        } else {
            Objects.requireNonNull(entryTime, "entryTime");
            if (!Double.isFinite(entryPrice)) {
                throw new IllegalArgumentException("entryPrice must be finite when executable");
            }
        }
    }

    public boolean eligibleForDiscovery() {
        return status == LabelStatus.COMPLETE_48_BARS || status == LabelStatus.TRADING_CLOSE;
    }

    private static Map<Integer, Double> orderedCopy(Map<Integer, Double> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<Integer, Double> ordered = new TreeMap<>();
        values.forEach((horizon, value) -> {
            if (horizon == null || horizon <= 0 || value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must contain positive horizons and finite values");
            }
            ordered.put(horizon, value);
        });
        return Collections.unmodifiableMap(ordered);
    }

    public enum ExcursionOrder {
        MFE_THEN_MAE,
        MAE_THEN_MFE,
        SIMULTANEOUS
    }
}
