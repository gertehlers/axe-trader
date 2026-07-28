package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministically mines depth-three, observable-only rule families from a derivation set. */
public final class ShallowRuleMiner {

    private static final int MAX_TREE_DEPTH = 3;
    private static final int MAX_RULE_CLAUSES = 4;
    private static final int MINIMUM_ZONES_PER_LEAF = 10;
    private static final int MAX_FAMILIES_PER_DIRECTION = 10;
    private static final Comparator<OpportunityScore> SCORE_ORDER = Comparator
            .comparing((OpportunityScore score) -> score.labelledObservation().state().id().signalTime())
            .thenComparing(score -> score.labelledObservation().state().id().instrument())
            .thenComparingInt(score -> score.labelledObservation().state().id().timeframeMinutes())
            .thenComparingInt(score -> score.labelledObservation().state().signalIndex());
    private static final Comparator<RuleClause> CLAUSE_ORDER = Comparator
            .comparing(RuleClause::feature)
            .thenComparing(RuleClause::operator)
            .thenComparingDouble(RuleClause::threshold);

    /** Mines from zone members when a caller only has the existing independent-zone output. */
    public List<CandidateRule> mine(List<OpportunityZone> zones) {
        Objects.requireNonNull(zones, "zones");
        List<OpportunityScore> scores = zones.stream().flatMap(zone -> zone.scores().stream()).toList();
        return mine(scores, zones);
    }

    /**
     * Mines one direction at a time, using all scored derivation observations for split quality and
     * opportunity zones solely for independent evidence counts.
     */
    public List<CandidateRule> mine(List<OpportunityScore> derivationScores, List<OpportunityZone> zones) {
        Objects.requireNonNull(derivationScores, "derivationScores");
        Objects.requireNonNull(zones, "zones");
        Map<ObservationId, Set<String>> zoneMembership = ConditionalSliceAnalyzer.zoneMembership(zones);
        Map<Direction, List<OpportunityScore>> byDirection = new EnumMap<>(Direction.class);
        for (OpportunityScore score : derivationScores) {
            Objects.requireNonNull(score, "score");
            if (score.labelledObservation().label().eligibleForDiscovery()) {
                byDirection.computeIfAbsent(score.labelledObservation().state().id().direction(), ignored -> new ArrayList<>())
                        .add(score);
            }
        }

        List<CandidateRule> allDirections = new ArrayList<>();
        for (Map.Entry<Direction, List<OpportunityScore>> entry : byDirection.entrySet()) {
            List<OpportunityScore> scores = entry.getValue().stream().sorted(SCORE_ORDER).toList();
            allDirections.addAll(mineDirection(entry.getKey(), scores, zoneMembership));
        }
        allDirections.sort(Comparator.comparing(CandidateRule::direction).thenComparing(CandidateRule::id));
        return List.copyOf(allDirections);
    }

    private static List<CandidateRule> mineDirection(
            Direction direction,
            List<OpportunityScore> scores,
            Map<ObservationId, Set<String>> zoneMembership) {
        if (scores.isEmpty()) {
            return List.of();
        }
        Set<String> features = commonObservableFeatures(scores);
        if (features.isEmpty()) {
            return List.of();
        }
        List<CandidateRule> leaves = new ArrayList<>();
        explore(new Node(scores, List.of()), features, zoneMembership, leaves);
        return mergeFamilies(leaves).stream()
                .sorted(Comparator.comparingDouble(CandidateRule::meanScore).reversed()
                        .thenComparing(Comparator.comparingDouble(CandidateRule::meanNetMfeAtr).reversed())
                        .thenComparing(CandidateRule::id))
                .limit(MAX_FAMILIES_PER_DIRECTION)
                .toList();
    }

    private static void explore(
            Node node,
            Set<String> features,
            Map<ObservationId, Set<String>> zoneMembership,
            List<CandidateRule> leaves) {
        if (!node.clauses().isEmpty() && isPositiveLeaf(node, zoneMembership)) {
            leaves.add(CandidateRule.create(
                    node.samples().getFirst().labelledObservation().state().id().direction(),
                    node.clauses(),
                    independentZones(node.samples(), zoneMembership),
                    ConditionalSliceAnalyzer.meanNetMfeAtr(node.samples()),
                    zoneWeightedMeanScore(node.samples(), zoneMembership)));
        }
        if (node.clauses().size() == MAX_TREE_DEPTH || node.clauses().size() == MAX_RULE_CLAUSES) {
            return;
        }
        bestSplit(node, features, zoneMembership).ifPresent(split -> explore(split, features, zoneMembership, leaves));
    }

    private static java.util.Optional<Node> bestSplit(
            Node parent,
            Set<String> features,
            Map<ObservationId, Set<String>> zoneMembership) {
        double parentScore = zoneWeightedMeanScore(parent.samples(), zoneMembership);
        SplitCandidate best = null;
        for (String feature : features) {
            if (alreadyConstrained(parent.clauses(), feature)) {
                continue;
            }
            for (double threshold : ConditionalSliceAnalyzer.thresholds(parent.samples(), feature)) {
                for (RuleClause.Operator operator : RuleClause.Operator.values()) {
                    RuleClause clause = new RuleClause(feature, operator, threshold);
                    List<OpportunityScore> childSamples = parent.samples().stream()
                            .filter(score -> clause.matches(score.labelledObservation().state().features()))
                            .toList();
                    if (childSamples.isEmpty() || childSamples.size() == parent.samples().size()
                            || independentZones(childSamples, zoneMembership) < MINIMUM_ZONES_PER_LEAF) {
                        continue;
                    }
                    SplitCandidate candidate = new SplitCandidate(
                            new Node(childSamples, append(parent.clauses(), clause)),
                            zoneWeightedMeanScore(childSamples, zoneMembership) - parentScore,
                            clause);
                    if (best == null || SPLIT_ORDER.compare(candidate, best) < 0) {
                        best = candidate;
                    }
                }
            }
        }
        return best == null ? java.util.Optional.empty() : java.util.Optional.of(best.node());
    }

    private static final Comparator<SplitCandidate> SPLIT_ORDER = Comparator
            .comparingDouble(SplitCandidate::improvement).reversed()
            .thenComparing(candidate -> candidate.clause().feature())
            .thenComparing(candidate -> candidate.clause().operator())
            .thenComparingDouble(candidate -> candidate.clause().threshold());

    private static boolean isPositiveLeaf(Node node, Map<ObservationId, Set<String>> zoneMembership) {
        return independentZones(node.samples(), zoneMembership) >= MINIMUM_ZONES_PER_LEAF
                && ConditionalSliceAnalyzer.meanNetMfeAtr(node.samples()) > 0.0;
    }

    private static double zoneWeightedMeanScore(
            List<OpportunityScore> samples, Map<ObservationId, Set<String>> zoneMembership) {
        Map<String, List<OpportunityScore>> scoresByZone = new HashMap<>();
        List<OpportunityScore> unzoned = new ArrayList<>();
        for (OpportunityScore score : samples) {
            Set<String> zones = zoneMembership.get(score.labelledObservation().state().id());
            if (zones == null || zones.isEmpty()) {
                unzoned.add(score);
            } else {
                for (String zone : zones) {
                    scoresByZone.computeIfAbsent(zone, ignored -> new ArrayList<>()).add(score);
                }
            }
        }
        List<Double> unitScores = new ArrayList<>();
        scoresByZone.values().forEach(zoneScores -> unitScores.add(
                zoneScores.stream().mapToDouble(OpportunityScore::compositeScore).average().orElseThrow()));
        unzoned.forEach(score -> unitScores.add(score.compositeScore()));
        return unitScores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static int independentZones(List<OpportunityScore> scores, Map<ObservationId, Set<String>> zoneMembership) {
        return ConditionalSliceAnalyzer.independentZoneCount(scores, zoneMembership);
    }

    private static Set<String> commonObservableFeatures(List<OpportunityScore> scores) {
        Set<String> features = new TreeSet<>();
        features.addAll(scores.getFirst().labelledObservation().state().features().values().keySet());
        for (OpportunityScore score : scores) {
            features.retainAll(score.labelledObservation().state().features().values().keySet());
        }
        features.removeIf(ShallowRuleMiner::isLabelField);
        return features;
    }

    private static boolean isLabelField(String feature) {
        String lowerCase = feature.toLowerCase(java.util.Locale.ROOT);
        return lowerCase.startsWith("label.") || lowerCase.startsWith("label_")
                || lowerCase.startsWith("forward.") || lowerCase.startsWith("forward_");
    }

    private static boolean alreadyConstrained(List<RuleClause> clauses, String feature) {
        return clauses.stream().anyMatch(clause -> clause.feature().equals(feature));
    }

    private static List<RuleClause> append(List<RuleClause> clauses, RuleClause clause) {
        List<RuleClause> result = new ArrayList<>(clauses);
        result.add(clause);
        result.sort(CLAUSE_ORDER);
        return List.copyOf(result);
    }

    private static List<CandidateRule> mergeFamilies(List<CandidateRule> candidates) {
        Map<String, CandidateRule> bestByFeatureOperatorSequence = new HashMap<>();
        for (CandidateRule candidate : candidates) {
            String family = candidate.direction() + "|" + candidate.clauses().stream()
                    .map(clause -> clause.feature() + "|" + clause.operator())
                    .reduce((left, right) -> left + "|" + right)
                    .orElseThrow();
            CandidateRule existing = bestByFeatureOperatorSequence.get(family);
            if (existing == null || CANDIDATE_ORDER.compare(candidate, existing) < 0) {
                bestByFeatureOperatorSequence.put(family, candidate);
            }
        }
        return List.copyOf(bestByFeatureOperatorSequence.values());
    }

    private static final Comparator<CandidateRule> CANDIDATE_ORDER = Comparator
            .comparingDouble(CandidateRule::meanScore).reversed()
            .thenComparing(Comparator.comparingDouble(CandidateRule::meanNetMfeAtr).reversed())
            .thenComparing(CandidateRule::id);

    private record Node(List<OpportunityScore> samples, List<RuleClause> clauses) {
    }

    private record SplitCandidate(Node node, double improvement, RuleClause clause) {
    }
}
