package io.g3tech.axetrader.backtest.discovery.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A finite, immutable feature map with a stable lexicographic iteration and JSON order.
 */
public record FeatureVector(Map<String, Double> values) {

    public FeatureVector {
        Objects.requireNonNull(values, "values");
        TreeMap<String, Double> ordered = new TreeMap<>();
        values.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("feature names must not be blank");
            }
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("feature '" + name + "' must be finite");
            }
            ordered.put(name, value);
        });
        values = Collections.unmodifiableMap(ordered);
    }

    @Override
    @JsonValue
    public Map<String, Double> values() {
        return values;
    }

    public double required(String name) {
        Double value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required feature: " + name);
        }
        return value;
    }
}
