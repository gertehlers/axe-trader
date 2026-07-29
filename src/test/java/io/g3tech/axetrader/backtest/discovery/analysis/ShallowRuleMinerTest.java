package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.runner.Direction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ShallowRuleMinerTest {

    @Test
    void minesTheTwoObservableConditionsAsOneDeterministicIndependentZoneRule() {
        Fixture fixture = fixture();
        List<OpportunityScore> shuffled = new ArrayList<>(fixture.scores());
        Collections.shuffle(shuffled, new Random(7));

        List<CandidateRule> forward = new ShallowRuleMiner().mine(fixture.scores(), fixture.zones());
        List<CandidateRule> shuffledResult = new ShallowRuleMiner().mine(shuffled, fixture.zones().reversed());

        assertThat(forward).hasSize(1);
        CandidateRule rule = forward.getFirst();
        assertThat(rule.clauses()).extracting(RuleClause::feature)
                .containsExactly("support.strengthening", "trend.slope_rising");
        assertThat(rule.clauses()).hasSizeLessThanOrEqualTo(3);
        assertThat(rule.clauses()).hasSizeLessThanOrEqualTo(4);
        assertThat(rule.independentZones()).isEqualTo(10);
        assertThat(rule.matches(state(99, 1, 1, 0))).isTrue();
        assertThat(rule.matches(state(99, 1, 0, 0))).isFalse();
        assertThat(rule.clauses()).extracting(RuleClause::feature)
                .noneMatch(feature -> feature.startsWith("label."));
        assertThat(forward).hasSizeLessThanOrEqualTo(10);
        assertThat(shuffledResult).isEqualTo(forward);
    }

    @Test
    void hashesCanonicalClauseJsonIndependentlyOfInputClauseOrder() {
        List<RuleClause> clauses = List.of(
                new RuleClause("trend.slope_rising", RuleClause.Operator.GT, 0.0),
                new RuleClause("support.strengthening", RuleClause.Operator.GT, 0.0));

        CandidateRule first = CandidateRule.create(Direction.LONG, clauses, 10, 1.2, 0.9);
        CandidateRule second = CandidateRule.create(Direction.LONG, clauses.reversed(), 10, 1.2, 0.9);

        assertThat(first.id()).matches("[0-9a-f]{64}");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.clauses()).extracting(RuleClause::feature)
                .containsExactly("support.strengthening", "trend.slope_rising");
    }

    @Test
    void usesOnlyOriginalDerivationPercentilesAtNestedTreeNodes() {
        List<OpportunityScore> scores = new ArrayList<>();
        List<OpportunityZone> zones = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            double nested = 30 + (index / 10) * 10;
            double score = nested == 60 ? .9 : .1;
            double mfeAtr = nested == 60 ? 1.2 : -.1;
            OpportunityScore observation = score(index, 1, nested, mfeAtr, score, OpportunityClass.RUN);
            scores.add(observation);
            zones.add(zone("nested-" + index, observation));
        }
        for (int index = 0; index < 40; index++) {
            scores.add(score(100 + index, 0, 0, -8.0, .1, OpportunityClass.CHOP));
        }

        List<CandidateRule> rules = new ShallowRuleMiner().mine(scores, zones);

        assertThat(rules).isNotEmpty();
        assertThat(rules).allSatisfy(rule -> assertThat(rule.clauses()).allSatisfy(clause ->
                assertThat(ConditionalSliceAnalyzer.thresholds(scores, clause.feature())).contains(clause.threshold())));
    }

    @Test
    void retainsPositiveLeavesFromBothSidesOfTheBestSplit() {
        List<OpportunityScore> scores = new ArrayList<>();
        List<OpportunityZone> zones = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            double regime = index < 10 ? 0 : 1;
            double composite = regime == 0 ? .8 : .9;
            OpportunityScore observation = score(index, regime, 0, 1.2, composite, OpportunityClass.RUN);
            scores.add(observation);
            zones.add(zone("regime-" + index, observation));
        }

        List<CandidateRule> rules = new ShallowRuleMiner().mine(scores, zones);

        assertThat(rules).hasSize(2);
        assertThat(rules).allSatisfy(rule -> assertThat(rule.clauses()).extracting(RuleClause::feature)
                .containsExactly("support.strengthening"));
        assertThat(rules).anySatisfy(rule -> assertThat(rule.matches(state(90, 0, 0, 0))).isTrue());
        assertThat(rules).anySatisfy(rule -> assertThat(rule.matches(state(91, 1, 0, 0))).isTrue());
    }

    private static Fixture fixture() {
        List<OpportunityScore> scores = new ArrayList<>();
        List<OpportunityZone> zones = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            OpportunityScore first = score(index * 2, 1, 1, 1.2, .9, OpportunityClass.RUN);
            OpportunityScore adjacent = score(index * 2 + 1, 1, 1, 1.2, .9, OpportunityClass.RUN);
            scores.add(first);
            scores.add(adjacent);
            Instant time = first.labelledObservation().state().id().signalTime();
            zones.add(new OpportunityZone("run-" + index, Direction.LONG, time,
                    adjacent.labelledObservation().state().id().signalTime(), OpportunityScorerV1.SCORE_VERSION,
                    List.of(first, adjacent)));
        }
        for (int index = 0; index < 4; index++) {
            scores.add(score(100 + index, 1, 0, -8.0, .1, OpportunityClass.CHOP));
            scores.add(score(110 + index, 0, 1, -8.0, .1, OpportunityClass.CHOP));
        }
        return new Fixture(List.copyOf(scores), List.copyOf(zones));
    }

    private static OpportunityScore score(
            int index, double support, double trend, double mfeAtr, double composite, OpportunityClass classification) {
        ObservableState state = state(index, support, trend, mfeAtr);
        return new OpportunityScore(labelled(state, mfeAtr), composite, composite, composite, composite, composite,
                composite, classification, OpportunityScorerV1.SCORE_VERSION);
    }

    private static ObservableState state(int index, double support, double trend, double labelValue) {
        Instant time = Instant.parse("2025-02-01T00:00:00Z").plusSeconds(index * 300L);
        return new ObservableState(new ObservationId("US500", 5, time, Direction.LONG), index, index + 1, 1, 240,
                new FeatureVector(Map.of(
                        "support.strengthening", support,
                        "trend.slope_rising", trend,
                        "label.net_mfe_atr", labelValue)));
    }

    private static LabelledObservation labelled(ObservableState state, double mfeAtr) {
        return new LabelledObservation(state, new ForwardPathLabel(LabelStatus.COMPLETE_48_BARS, 100,
                state.id().signalTime().plusSeconds(300), List.of(), mfeAtr, mfeAtr, -.5, -.5, true,
                ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE, Map.of(), Map.of(), .5, 4, 1));
    }

    private static OpportunityZone zone(String zoneId, OpportunityScore score) {
        Instant time = score.labelledObservation().state().id().signalTime();
        return new OpportunityZone(zoneId, Direction.LONG, time, time, OpportunityScorerV1.SCORE_VERSION, List.of(score));
    }

    private record Fixture(List<OpportunityScore> scores, List<OpportunityZone> zones) {
    }
}
