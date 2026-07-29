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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.data.Offset.offset;

class ConditionalSliceAnalyzerTest {

    @Test
    void reportsConditionalNetMfeDeltaIndependentZonesAndMonthlyCoverage() {
        OpportunityScore februaryRun = score("2025-02-01T00:05:00Z", 0, 1.0, 1.2);
        OpportunityScore februaryChop = score("2025-02-01T00:10:00Z", 1, 0.0, -0.4);
        OpportunityScore marchRun = score("2025-03-01T00:05:00Z", 2, 1.0, 1.2);
        OpportunityScore marchChop = score("2025-03-01T00:10:00Z", 3, 0.0, -0.4);

        ConditionalSlice slice = new ConditionalSliceAnalyzer().analyze(
                        List.of(februaryRun, februaryChop, marchRun, marchChop),
                        List.of(zone(februaryRun), zone(marchRun)))
                .stream()
                .filter(candidate -> candidate.feature().equals("pillar.volume_trend.activated"))
                .filter(candidate -> candidate.operator() == RuleClause.Operator.GTE)
                .filter(candidate -> candidate.threshold() == 1.0)
                .findFirst()
                .orElseThrow();

        assertThat(slice.meanNetMfeAtr()).isCloseTo(1.2, offset(0.0000001));
        assertThat(slice.baselineMeanNetMfeAtr()).isCloseTo(0.4, offset(0.0000001));
        assertThat(slice.netMfeDeltaAtr()).isCloseTo(0.8, offset(0.0000001));
        assertThat(slice.independentZones()).isEqualTo(2);
        assertThat(slice.monthlyCoverage()).containsExactly("2025-02", "2025-03");
    }

    @Test
    void usesOnlyDerivationSetTwentiethThroughEightiethPercentileThresholds() {
        List<OpportunityScore> scores = List.of(
                numericScore(0, 0), numericScore(1, 10), numericScore(2, 20),
                numericScore(3, 30), numericScore(4, 40));

        List<Double> thresholds = new ConditionalSliceAnalyzer().analyze(scores, List.of()).stream()
                .filter(slice -> slice.feature().equals("trend.slope"))
                .filter(slice -> slice.operator() == RuleClause.Operator.GTE)
                .map(ConditionalSlice::threshold)
                .toList();

        assertThat(thresholds).containsExactly(0.0, 10.0, 20.0, 30.0);
    }

    @Test
    void excludesLabelFieldsFromConditionalSlicesAndPublicRuleConstruction() {
        OpportunityScore first = score("2025-04-01T00:00:00Z", 0, 1.0, 1.2,
                Map.of("observable.trend", 1.0, "label.mfe_atr", 1.2));
        OpportunityScore second = score("2025-04-01T00:00:00Z", 1, 0.0, -0.4,
                Map.of("observable.trend", 0.0, "label.mfe_atr", -0.4));

        assertThat(new ConditionalSliceAnalyzer().analyze(List.of(first, second), List.of()))
                .extracting(ConditionalSlice::feature)
                .containsOnly("observable.trend");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RuleClause("label.mfe_atr", RuleClause.Operator.GTE, 0.0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CandidateRule.create(Direction.LONG,
                        List.of(new RuleClause("forward.path_score", RuleClause.Operator.GTE, 0.0)), 10, 1.0, 0.5));
    }

    private static OpportunityScore numericScore(int index, double slope) {
        return score("2025-04-01T00:00:00Z", index, slope, slope / 10.0,
                Map.of("trend.slope", slope));
    }

    private static OpportunityScore score(String timestamp, int signalIndex, double activated, double mfeAtr) {
        return score(timestamp, signalIndex, activated, mfeAtr,
                Map.of("pillar.volume_trend.activated", activated));
    }

    private static OpportunityScore score(
            String timestamp, int signalIndex, double activated, double mfeAtr, Map<String, Double> features) {
        Instant signalTime = Instant.parse(timestamp).plusSeconds(signalIndex * 300L);
        LabelledObservation observation = new LabelledObservation(
                new ObservableState(new ObservationId("US500", 5, signalTime, Direction.LONG), signalIndex,
                        signalIndex + 1, 1.0, 240, new FeatureVector(features)),
                new ForwardPathLabel(LabelStatus.COMPLETE_48_BARS, 100.0, signalTime.plusSeconds(300), List.of(),
                        mfeAtr, mfeAtr, -0.5, -0.5, true, ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE,
                        Map.of(), Map.of(), 0.5, 4, 1));
        return new OpportunityScore(observation, 0.5, 0.5, 0.5, 0.5, 0.5, OpportunityClass.RUN,
                OpportunityScorerV1.SCORE_VERSION);
    }

    private static OpportunityZone zone(OpportunityScore score) {
        Instant signalTime = score.labelledObservation().state().id().signalTime();
        return new OpportunityZone("zone-" + signalTime, Direction.LONG, signalTime, signalTime,
                OpportunityScorerV1.SCORE_VERSION, List.of(score));
    }
}
