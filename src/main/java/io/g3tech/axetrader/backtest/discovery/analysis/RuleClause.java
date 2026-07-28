package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;

import java.util.Objects;

/** One finite, backward-only numeric predicate in a candidate entry rule. */
public record RuleClause(String feature, Operator operator, double threshold) {

    public RuleClause {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(operator, "operator");
        if (feature.isBlank() || !Double.isFinite(threshold)) {
            throw new IllegalArgumentException("rule clauses need a non-blank feature and finite threshold");
        }
    }

    public boolean matches(FeatureVector vector) {
        double value = Objects.requireNonNull(vector, "vector").required(feature);
        return switch (operator) {
            case LT -> value < threshold;
            case LTE -> value <= threshold;
            case GT -> value > threshold;
            case GTE -> value >= threshold;
        };
    }

    public enum Operator {
        LT, LTE, GT, GTE
    }
}
