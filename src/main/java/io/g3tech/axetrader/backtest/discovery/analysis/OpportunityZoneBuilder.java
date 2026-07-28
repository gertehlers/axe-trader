package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.runner.Direction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds independent V1 opportunity zones from chronological score output. */
public final class OpportunityZoneBuilder {

    private static final int MAX_NON_RUN_BARS_BETWEEN_RUNS = 1;
    private static final int MAX_MFE_BARS_APART = 6;

    public List<OpportunityZone> build(List<OpportunityScore> scores) {
        Objects.requireNonNull(scores, "scores");
        Map<Direction, List<OpportunityScore>> byDirection = new EnumMap<>(Direction.class);
        for (OpportunityScore score : scores) {
            Objects.requireNonNull(score, "score");
            byDirection.computeIfAbsent(score.labelledObservation().state().id().direction(), ignored -> new ArrayList<>())
                    .add(score);
        }

        List<OpportunityZone> zones = new ArrayList<>();
        for (List<OpportunityScore> directionScores : byDirection.values()) {
            directionScores.sort(scoreOrder());
            zones.addAll(buildDirection(directionScores));
        }
        zones.sort(Comparator.comparing(OpportunityZone::firstSignalTime)
                .thenComparing(OpportunityZone::direction)
                .thenComparing(OpportunityZone::lastSignalTime));
        return List.copyOf(zones);
    }

    private static List<OpportunityZone> buildDirection(List<OpportunityScore> scores) {
        List<List<OpportunityScore>> groupedRuns = new ArrayList<>();
        List<OpportunityScore> active = null;
        OpportunityScore previousRun = null;
        int consecutiveNonRuns = 0;
        for (OpportunityScore score : scores) {
            if (score.opportunityClass() != OpportunityClass.RUN) {
                consecutiveNonRuns++;
                if (consecutiveNonRuns > MAX_NON_RUN_BARS_BETWEEN_RUNS) {
                    active = null;
                    previousRun = null;
                }
                continue;
            }

            boolean joinsActiveZone = active != null
                    && consecutiveNonRuns <= MAX_NON_RUN_BARS_BETWEEN_RUNS
                    && signalBarsApart(previousRun, score) <= MAX_NON_RUN_BARS_BETWEEN_RUNS + 1
                    && mfeTimesAreNoMoreThanSixBarsApart(previousRun, score);
            if (!joinsActiveZone) {
                active = new ArrayList<>();
                groupedRuns.add(active);
            }
            active.add(score);
            previousRun = score;
            consecutiveNonRuns = 0;
        }
        return groupedRuns.stream().map(OpportunityZoneBuilder::activeZone).toList();
    }

    private static OpportunityZone activeZone(List<OpportunityScore> scores) {
        OpportunityScore first = scores.getFirst();
        OpportunityScore last = scores.getLast();
        Instant firstTime = first.labelledObservation().state().id().signalTime();
        Instant lastTime = last.labelledObservation().state().id().signalTime();
        return new OpportunityZone(
                stableId(first.labelledObservation().state().id().direction(), firstTime, lastTime, first.scoreVersion()),
                first.labelledObservation().state().id().direction(),
                firstTime,
                lastTime,
                first.scoreVersion(),
                scores);
    }

    private static long signalBarsApart(OpportunityScore earlier, OpportunityScore later) {
        return Math.abs((long) later.labelledObservation().state().signalIndex()
                - earlier.labelledObservation().state().signalIndex());
    }

    private static boolean mfeTimesAreNoMoreThanSixBarsApart(OpportunityScore earlier, OpportunityScore later) {
        Instant earlierMfe = mfeTime(earlier);
        Instant laterMfe = mfeTime(later);
        long barSeconds = Math.max(
                earlier.labelledObservation().state().id().timeframeMinutes(),
                later.labelledObservation().state().id().timeframeMinutes()) * 60L;
        return Math.abs(Duration.between(earlierMfe, laterMfe).toSeconds())
                <= MAX_MFE_BARS_APART * barSeconds;
    }

    private static Instant mfeTime(OpportunityScore score) {
        return score.labelledObservation().label().entryTime().plusSeconds(
                score.labelledObservation().label().timeToMfeBars()
                        * score.labelledObservation().state().id().timeframeMinutes() * 60L);
    }

    private static Comparator<OpportunityScore> scoreOrder() {
        return Comparator
                .comparing((OpportunityScore score) -> score.labelledObservation().state().id().signalTime())
                .thenComparingInt(score -> score.labelledObservation().state().signalIndex());
    }

    private static String stableId(Direction direction, Instant first, Instant last, String scoreVersion) {
        String source = direction + "|" + first + "|" + last + "|" + scoreVersion;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("opportunity-zone-");
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}
