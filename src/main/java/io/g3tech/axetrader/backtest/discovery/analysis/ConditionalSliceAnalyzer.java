package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Produces deterministic observable, one-clause outcome slices from one derivation set. */
public final class ConditionalSliceAnalyzer {

    private static final List<Double> PERCENTILES = List.of(0.2, 0.4, 0.6, 0.8);

    public List<ConditionalSlice> analyze(List<OpportunityScore> scores, List<OpportunityZone> zones) {
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(zones, "zones");
        Map<ObservationId, Set<String>> zonesByObservation = zoneMembership(zones);
        Map<Direction, Map<String, List<OpportunityScore>>> byDirectionAndFeature = new EnumMap<>(Direction.class);
        for (OpportunityScore score : scores) {
            Objects.requireNonNull(score, "score");
            if (!score.labelledObservation().label().eligibleForDiscovery()) {
                continue;
            }
            Direction direction = score.labelledObservation().state().id().direction();
            for (String feature : score.labelledObservation().state().features().values().keySet()) {
                byDirectionAndFeature.computeIfAbsent(direction, ignored -> new TreeMap<>())
                        .computeIfAbsent(feature, ignored -> new ArrayList<>())
                        .add(score);
            }
        }

        List<ConditionalSlice> slices = new ArrayList<>();
        for (Map.Entry<Direction, Map<String, List<OpportunityScore>>> directionEntry : byDirectionAndFeature.entrySet()) {
            for (Map.Entry<String, List<OpportunityScore>> featureEntry : directionEntry.getValue().entrySet()) {
                String feature = featureEntry.getKey();
                List<OpportunityScore> derivationScores = featureEntry.getValue();
                double baseline = meanNetMfeAtr(derivationScores);
                for (double threshold : thresholds(derivationScores, feature)) {
                    for (RuleClause.Operator operator : List.of(RuleClause.Operator.LTE, RuleClause.Operator.GTE)) {
                        RuleClause clause = new RuleClause(feature, operator, threshold);
                        List<OpportunityScore> matching = derivationScores.stream()
                                .filter(score -> clause.matches(score.labelledObservation().state().features()))
                                .toList();
                        if (!matching.isEmpty()) {
                            slices.add(new ConditionalSlice(
                                    directionEntry.getKey(), feature, operator, threshold,
                                    meanNetMfeAtr(matching), baseline,
                                    independentZoneCount(matching, zonesByObservation), monthlyCoverage(matching)));
                        }
                    }
                }
            }
        }
        slices.sort(Comparator.comparing(ConditionalSlice::direction)
                .thenComparing(ConditionalSlice::feature)
                .thenComparing(ConditionalSlice::operator)
                .thenComparingDouble(ConditionalSlice::threshold));
        return List.copyOf(slices);
    }

    static List<Double> thresholds(List<OpportunityScore> scores, String feature) {
        List<Double> values = scores.stream()
                .map(score -> score.labelledObservation().state().features().required(feature))
                .sorted()
                .toList();
        TreeSet<Double> thresholds = new TreeSet<>();
        for (double percentile : PERCENTILES) {
            thresholds.add(values.get((int) Math.ceil(percentile * values.size()) - 1));
        }
        return List.copyOf(thresholds);
    }

    static Map<ObservationId, Set<String>> zoneMembership(List<OpportunityZone> zones) {
        Map<ObservationId, Set<String>> result = new HashMap<>();
        for (OpportunityZone zone : zones) {
            Objects.requireNonNull(zone, "zone");
            for (OpportunityScore score : zone.scores()) {
                result.computeIfAbsent(score.labelledObservation().state().id(), ignored -> new HashSet<>()).add(zone.zoneId());
            }
        }
        return result;
    }

    static int independentZoneCount(List<OpportunityScore> scores, Map<ObservationId, Set<String>> zonesByObservation) {
        return scores.stream()
                .flatMap(score -> zonesByObservation
                        .getOrDefault(score.labelledObservation().state().id(), Set.of()).stream())
                .collect(java.util.stream.Collectors.toSet())
                .size();
    }

    static double meanNetMfeAtr(List<OpportunityScore> scores) {
        return scores.stream().mapToDouble(score -> score.labelledObservation().label().mfeAtr()).average().orElseThrow();
    }

    private static List<String> monthlyCoverage(List<OpportunityScore> scores) {
        return scores.stream()
                .map(score -> YearMonth.from(score.labelledObservation().state().id().signalTime().atZone(ZoneOffset.UTC)))
                .distinct()
                .sorted()
                .map(YearMonth::toString)
                .toList();
    }
}
