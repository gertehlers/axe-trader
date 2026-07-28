package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Generates and deterministically ranks the finite policy library for one entry rule. */
public final class ExitPolicyGenerator {

    private static final List<List<Double>> FRACTION_TEMPLATES = List.of(
            List.of(1.0), List.of(.5, .5), List.of(.33, .67), List.of(.67, .33),
            List.of(.33, .33, .34), List.of(.25, .35, .40), List.of(.5, .25, .25));

    public List<ExitPolicy> generate(CandidateRule rule, List<LabelledObservation> derivationOccurrences) {
        Objects.requireNonNull(rule, "rule");
        List<LabelledObservation> observations = List.copyOf(Objects.requireNonNull(
                derivationOccurrences, "derivationOccurrences")).stream()
                .filter(observation -> observation.label().eligibleForDiscovery())
                .filter(observation -> rule.matches(observation.state()))
                .filter(observation -> observation.label().mfeAtr() > 0.0)
                .toList();
        if (observations.isEmpty()) {
            return List.of();
        }

        List<Double> targets = distinctAscending(List.of(
                percentile(observations.stream().mapToDouble(o -> o.label().mfeAtr()).toArray(), .25),
                percentile(observations.stream().mapToDouble(o -> o.label().mfeAtr()).toArray(), .50),
                percentile(observations.stream().mapToDouble(o -> o.label().mfeAtr()).toArray(), .75)));
        List<Double> mae = observations.stream().mapToDouble(o -> -o.label().maeAtr()).filter(value -> value > 0.0).boxed().toList();
        if (mae.isEmpty()) {
            return List.of();
        }
        List<ExitPolicy.Stop> stops = List.of(
                new ExitPolicy.Stop(ExitPolicy.StopSource.MAE_P50,
                        percentile(mae.stream().mapToDouble(Double::doubleValue).toArray(), .50)),
                new ExitPolicy.Stop(ExitPolicy.StopSource.MAE_P75,
                        percentile(mae.stream().mapToDouble(Double::doubleValue).toArray(), .75)));

        List<ExitPolicy> policies = new ArrayList<>();
        for (List<Double> targetPlan : targetPlans(targets)) {
            for (List<Double> fractions : FRACTION_TEMPLATES) {
                if (fractions.size() != targetPlan.size()) {
                    continue;
                }
                List<ExitPolicy.Tier> tiers = new ArrayList<>();
                for (int i = 0; i < fractions.size(); i++) {
                    tiers.add(new ExitPolicy.Tier(fractions.get(i), targetPlan.get(i)));
                }
                for (ExitPolicy.Stop stop : stops) {
                    for (Ratchet ratchet : Ratchet.values()) {
                        policies.add(new ExitPolicy(rule, tiers, stop, ratchet, List.of(), 48));
                    }
                }
            }
        }
        return policies.stream().sorted(Comparator
                        .comparingDouble((ExitPolicy policy) -> score(policy, observations)).reversed()
                        .thenComparing(ExitPolicyGenerator::key))
                .limit(12)
                .toList();
    }

    private static double score(ExitPolicy policy, List<LabelledObservation> observations) {
        double net = 0.0;
        double drawdown = 0.0;
        for (LabelledObservation observation : observations) {
            double realised = 0.0;
            for (ExitPolicy.Tier tier : policy.tiers()) {
                realised += tier.fraction() * Math.min(observation.label().mfeAtr(), tier.targetAtr());
            }
            if (-observation.label().maeAtr() >= policy.stop().distanceAtr()) {
                realised = -policy.stop().distanceAtr();
            }
            net += realised;
            drawdown = Math.max(drawdown, -realised);
        }
        return net / Math.max(drawdown, .01);
    }

    private static List<List<Double>> targetPlans(List<Double> values) {
        List<List<Double>> plans = new ArrayList<>();
        for (double first : values) {
            plans.add(List.of(first));
        }
        for (int first = 0; first < values.size(); first++) {
            for (int second = first + 1; second < values.size(); second++) {
                plans.add(List.of(values.get(first), values.get(second)));
            }
        }
        if (values.size() == 3) {
            plans.add(values);
        }
        return plans;
    }

    private static List<Double> distinctAscending(List<Double> values) {
        return values.stream().distinct().sorted().toList();
    }

    private static double percentile(double[] values, double percentile) {
        java.util.Arrays.sort(values);
        return values[(int) Math.ceil(percentile * values.length) - 1];
    }

    private static String key(ExitPolicy policy) {
        return policy.tiers() + "|" + policy.stop() + "|" + policy.ratchet();
    }
}
