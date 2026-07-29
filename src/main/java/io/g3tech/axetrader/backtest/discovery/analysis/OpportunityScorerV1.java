package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** Scores completed forward labels independently for each direction and UTC calendar month. */
public final class OpportunityScorerV1 {

    public static final String SCORE_VERSION = "opportunity-v1";
    private static final double RUN_CUTOFF = 0.8;
    private static final double CHOP_CUTOFF = 0.4;

    public List<OpportunityScore> score(List<LabelledObservation> observations) {
        Objects.requireNonNull(observations, "observations");
        Map<ScoreGroup, List<LabelledObservation>> groups = new HashMap<>();
        for (LabelledObservation observation : observations) {
            validate(observation);
            groups.computeIfAbsent(groupFor(observation), ignored -> new ArrayList<>()).add(observation);
        }

        List<OpportunityScore> scores = new ArrayList<>(observations.size());
        for (List<LabelledObservation> group : groups.values()) {
            scores.addAll(scoreGroup(group));
        }
        scores.sort(scoreOrder());
        return List.copyOf(scores);
    }

    private static List<OpportunityScore> scoreGroup(List<LabelledObservation> observations) {
        Map<LabelledObservation, Double> netMfe = percentileRanks(observations, observation -> observation.label().mfeAtr(), true);
        Map<LabelledObservation, Double> rewardToRisk = percentileRanks(observations,
                observation -> observation.label().mfeAtr()
                        / Math.max(0.1, Math.abs(maeBeforeMfeAtr(observation))), true);
        Map<LabelledObservation, Double> efficiency = percentileRanks(observations,
                observation -> observation.label().directionalEfficiency(), true);
        Map<LabelledObservation, Double> timeToMfe = percentileRanks(observations,
                observation -> observation.label().timeToMfeBars(), false);
        Map<LabelledObservation, Double> twoSidedExcursion = percentileRanks(observations,
                observation -> observation.label().mfeAtr() + Math.abs(observation.label().maeAtr()), false);

        List<OpportunityScore> scores = new ArrayList<>(observations.size());
        for (LabelledObservation observation : observations) {
            double composite = (netMfe.get(observation)
                    + rewardToRisk.get(observation)
                    + efficiency.get(observation)
                    + timeToMfe.get(observation)
                    + twoSidedExcursion.get(observation)) / 5.0;
            scores.add(new OpportunityScore(
                    observation,
                    netMfe.get(observation),
                    rewardToRisk.get(observation),
                    efficiency.get(observation),
                    timeToMfe.get(observation),
                    twoSidedExcursion.get(observation),
                    composite,
                    classify(composite),
                    SCORE_VERSION));
        }
        return scores;
    }

    private static Map<LabelledObservation, Double> percentileRanks(
            List<LabelledObservation> observations,
            ToDoubleFunction<LabelledObservation> value,
            boolean higherIsBetter) {
        List<LabelledObservation> ordered = new ArrayList<>(observations);
        ordered.sort(Comparator.comparingDouble(value));
        Map<LabelledObservation, Double> ranks = new HashMap<>();
        int size = ordered.size();
        int index = 0;
        while (index < size) {
            int end = index + 1;
            double tiedValue = value.applyAsDouble(ordered.get(index));
            while (end < size && Double.compare(tiedValue, value.applyAsDouble(ordered.get(end))) == 0) {
                end++;
            }
            double averageRank = ((index + 1) + end) / 2.0;
            double percentile = size == 1 ? 1.0 : (averageRank - 1.0) / (size - 1.0);
            if (size > 1 && !higherIsBetter) {
                percentile = 1.0 - percentile;
            }
            for (int tied = index; tied < end; tied++) {
                ranks.put(ordered.get(tied), percentile);
            }
            index = end;
        }
        return ranks;
    }

    private static OpportunityClass classify(double composite) {
        if (composite >= RUN_CUTOFF) {
            return OpportunityClass.RUN;
        }
        if (composite <= CHOP_CUTOFF) {
            return OpportunityClass.CHOP;
        }
        return OpportunityClass.AMBIGUOUS;
    }

    private static double maeBeforeMfeAtr(LabelledObservation observation) {
        return observation.label().maeBeforeMfe() ? observation.label().maeAtr() : 0.0;
    }

    private static ScoreGroup groupFor(LabelledObservation observation) {
        return new ScoreGroup(
                observation.state().id().direction(),
                YearMonth.from(observation.state().id().signalTime().atZone(ZoneOffset.UTC)));
    }

    private static void validate(LabelledObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!observation.label().eligibleForDiscovery()) {
            throw new IllegalArgumentException("only complete or trading-close labels can be scored");
        }
        if (!Double.isFinite(observation.label().mfeAtr())
                || !Double.isFinite(observation.label().maeAtr())
                || !Double.isFinite(observation.label().directionalEfficiency())) {
            throw new IllegalArgumentException("score inputs must be finite");
        }
    }

    private static Comparator<OpportunityScore> scoreOrder() {
        return Comparator
                .comparing((OpportunityScore score) -> score.labelledObservation().state().id().signalTime())
                .thenComparing(score -> score.labelledObservation().state().id().direction())
                .thenComparing(score -> score.labelledObservation().state().id().instrument())
                .thenComparingInt(score -> score.labelledObservation().state().id().timeframeMinutes())
                .thenComparingInt(score -> score.labelledObservation().state().signalIndex());
    }

    private record ScoreGroup(Direction direction, YearMonth calendarMonth) {
    }
}
