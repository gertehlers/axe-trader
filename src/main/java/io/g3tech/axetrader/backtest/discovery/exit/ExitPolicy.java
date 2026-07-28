package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;

import java.util.List;
import java.util.Objects;

/** A frozen, bounded and fully observable exit policy for one entry-pattern family. */
public record ExitPolicy(
        CandidateRule rule,
        List<Tier> tiers,
        Stop stop,
        Ratchet ratchet,
        List<RuleClause> invalidationClauses,
        int maxHoldingBars) {

    public ExitPolicy {
        Objects.requireNonNull(rule, "rule");
        tiers = List.copyOf(Objects.requireNonNull(tiers, "tiers"));
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(ratchet, "ratchet");
        invalidationClauses = List.copyOf(Objects.requireNonNull(invalidationClauses, "invalidationClauses"));
        if (tiers.isEmpty() || tiers.size() > 3 || invalidationClauses.size() > 2 || maxHoldingBars != 48) {
            throw new IllegalArgumentException("policies need one to three tiers, at most two invalidations, and 48 bars");
        }
        double previousTarget = 0.0;
        double totalFraction = 0.0;
        for (Tier tier : tiers) {
            if (tier.targetAtr() <= previousTarget) {
                throw new IllegalArgumentException("tier targets must be strictly ascending");
            }
            previousTarget = tier.targetAtr();
            totalFraction += tier.fraction();
        }
        if (Math.abs(totalFraction - 1.0) > 1e-9) {
            throw new IllegalArgumentException("tier fractions must sum to one");
        }
    }

    public List<Double> targetsAtr() {
        return tiers.stream().map(Tier::targetAtr).toList();
    }

    public List<Double> fractions() {
        return tiers.stream().map(Tier::fraction).toList();
    }

    public record Tier(double fraction, double targetAtr) {
        public Tier {
            if (!Double.isFinite(fraction) || fraction <= 0.0 || !Double.isFinite(targetAtr) || targetAtr <= 0.0) {
                throw new IllegalArgumentException("tier fraction and target must be positive finite values");
            }
        }
    }

    public record Stop(StopSource source, double distanceAtr) {
        public Stop {
            Objects.requireNonNull(source, "source");
            if (!Double.isFinite(distanceAtr) || distanceAtr <= 0.0) {
                throw new IllegalArgumentException("stop distance must be positive and finite");
            }
        }
    }

    public enum StopSource {
        STRUCTURAL,
        MAE_P50,
        MAE_P75
    }
}
