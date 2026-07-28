package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicy;

import java.time.Instant;
import java.util.Objects;

/** Immutable candidate definition registered before its validation window is evaluated. */
public record FrozenCandidate(
        String id,
        CandidateRule rule,
        ExitPolicy exitPolicy,
        Instant derivationFrom,
        Instant derivationTo,
        String featureSchemaVersion,
        String scoreVersion) {

    public FrozenCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(exitPolicy, "exitPolicy");
        Objects.requireNonNull(derivationFrom, "derivationFrom");
        Objects.requireNonNull(derivationTo, "derivationTo");
        Objects.requireNonNull(featureSchemaVersion, "featureSchemaVersion");
        Objects.requireNonNull(scoreVersion, "scoreVersion");
        if (id.isBlank() || featureSchemaVersion.isBlank() || scoreVersion.isBlank()
                || !derivationFrom.isBefore(derivationTo)) {
            throw new IllegalArgumentException("frozen candidate needs an id, versions, and a non-empty derivation window");
        }
        if (!rule.equals(exitPolicy.rule())) {
            throw new IllegalArgumentException("exit policy must be frozen for the candidate rule");
        }
    }
}
