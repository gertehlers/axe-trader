package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** An independent reporting zone made only from qualifying (RUN) observations. */
public record OpportunityZone(
        String zoneId,
        Direction direction,
        Instant firstSignalTime,
        Instant lastSignalTime,
        String scoreVersion,
        List<OpportunityScore> scores) {

    public OpportunityZone {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(firstSignalTime, "firstSignalTime");
        Objects.requireNonNull(lastSignalTime, "lastSignalTime");
        Objects.requireNonNull(scoreVersion, "scoreVersion");
        scores = List.copyOf(Objects.requireNonNull(scores, "scores"));
        if (zoneId.isBlank() || scoreVersion.isBlank() || scores.isEmpty()) {
            throw new IllegalArgumentException("zones need an id, score version, and at least one score");
        }
        if (lastSignalTime.isBefore(firstSignalTime)) {
            throw new IllegalArgumentException("zone end cannot precede its start");
        }
        for (OpportunityScore score : scores) {
            if (score.opportunityClass() != OpportunityClass.RUN
                    || score.labelledObservation().state().id().direction() != direction
                    || !score.scoreVersion().equals(scoreVersion)) {
                throw new IllegalArgumentException("zone scores must be same-direction V1 runs");
            }
        }
    }

    /** Raw label joins remain available for reporting without reconstructing them from a score. */
    public List<LabelledObservation> labelledObservations() {
        return scores.stream().map(OpportunityScore::labelledObservation).toList();
    }
}
