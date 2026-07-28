package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;
import io.g3tech.axetrader.backtest.discovery.model.PathPoint;
import io.g3tech.axetrader.backtest.runner.Direction;

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
        List<Double> mae = observations.stream()
                .filter(observation -> observation.label().maeBeforeMfe())
                .mapToDouble(observation -> -observation.label().maeAtr())
                .filter(value -> value > 0.0)
                .boxed()
                .toList();
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
                        .comparingDouble((ExitPolicy policy) -> metrics(policy, observations).netProfitOverMaxDrawdown())
                        .reversed()
                        .thenComparing(ExitPolicyGenerator::key))
                .limit(12)
                .toList();
    }

    /** Derivation profit and peak-to-trough risk for one frozen policy, in entry-time ATR units. */
    static RankingMetrics metrics(ExitPolicy policy, List<LabelledObservation> observations) {
        Objects.requireNonNull(policy, "policy");
        List<LabelledObservation> ordered = List.copyOf(Objects.requireNonNull(observations, "observations")).stream()
                .sorted(Comparator.comparing((LabelledObservation observation) -> observation.state().id().signalTime())
                        .thenComparingInt(observation -> observation.state().signalIndex())
                        .thenComparing(observation -> observation.state().id().instrument()))
                .toList();
        double net = 0.0;
        double equity = 0.0;
        double peak = 0.0;
        double drawdown = 0.0;
        for (LabelledObservation observation : ordered) {
            double realised = policyPnlAtr(policy, observation);
            net += realised;
            equity += realised;
            peak = Math.max(peak, equity);
            drawdown = Math.max(drawdown, peak - equity);
        }
        return new RankingMetrics(net, drawdown, ratio(net, drawdown));
    }

    private static double policyPnlAtr(ExitPolicy policy, LabelledObservation observation) {
        double atr = observation.state().entryAtr();
        double entry = observation.label().entryPrice();
        Direction direction = policy.rule().direction();
        boolean isLong = direction == Direction.LONG;
        double stop = isLong ? entry - policy.stop().distanceAtr() * atr : entry + policy.stop().distanceAtr() * atr;
        double realised = 0.0;
        double remaining = 1.0;
        int nextTier = 0;
        List<PathPoint> path = observation.label().exitPath();
        int lastPoint = Math.min(path.size(), policy.maxHoldingBars());

        for (int offset = 0; offset < lastPoint && nextTier < policy.tiers().size(); offset++) {
            PathPoint point = path.get(offset);
            boolean stopHit = isLong ? point.lowPrice() <= stop : point.highPrice() >= stop;
            if (stopHit) {
                return realised + remaining * pnlAtr(direction, entry, stop, atr);
            }
            while (nextTier < policy.tiers().size()) {
                ExitPolicy.Tier tier = policy.tiers().get(nextTier);
                double target = isLong ? entry + tier.targetAtr() * atr : entry - tier.targetAtr() * atr;
                boolean targetHit = isLong ? point.highPrice() >= target : point.lowPrice() <= target;
                if (!targetHit) {
                    break;
                }
                realised += tier.fraction() * pnlAtr(direction, entry, target, atr);
                remaining -= tier.fraction();
                nextTier++;
            }
            if (nextTier >= policy.tiers().size()) {
                return realised;
            }
            stop = ratchetedStop(policy, nextTier, direction, entry, stop, atr);
        }
        return lastPoint == 0 ? 0.0
                : realised + remaining * pnlAtr(direction, entry, path.get(lastPoint - 1).closePrice(), atr);
    }

    private static double ratchetedStop(
            ExitPolicy policy, int tiersFilled, Direction direction, double entry, double currentStop, double atr) {
        boolean isLong = direction == Direction.LONG;
        return switch (policy.ratchet()) {
            case NONE -> currentStop;
            case BREAKEVEN_AFTER_T1 -> {
                if (tiersFilled >= 2) {
                    double t1 = policy.tiers().getFirst().targetAtr() * atr;
                    yield isLong ? entry + t1 : entry - t1;
                }
                yield tiersFilled >= 1 ? entry : currentStop;
            }
            case LAGGED -> tiersFilled >= 2 ? entry : currentStop;
        };
    }

    private static double pnlAtr(Direction direction, double entry, double exit, double atr) {
        return (direction == Direction.LONG ? exit - entry : entry - exit) / atr;
    }

    private static double ratio(double netProfit, double maxDrawdown) {
        if (maxDrawdown == 0.0) {
            return netProfit > 0.0 ? Double.POSITIVE_INFINITY
                    : netProfit < 0.0 ? Double.NEGATIVE_INFINITY : 0.0;
        }
        return netProfit / maxDrawdown;
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

    record RankingMetrics(double netProfit, double maxDrawdown, double netProfitOverMaxDrawdown) {
    }
}
