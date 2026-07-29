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
import static org.assertj.core.data.Offset.offset;

class OpportunityScorerV1Test {

    @Test
    void classifiesTenOrderedPathsByTheirAverageComponentPercentile() {
        List<LabelledObservation> observations = List.of(
                observation("2026-02-01T00:05:00Z", 0, 1),
                observation("2026-02-01T00:10:00Z", 1, 2),
                observation("2026-02-01T00:15:00Z", 2, 3),
                observation("2026-02-01T00:20:00Z", 3, 4),
                observation("2026-02-01T00:25:00Z", 4, 5),
                observation("2026-02-01T00:30:00Z", 5, 6),
                observation("2026-02-01T00:35:00Z", 6, 7),
                observation("2026-02-01T00:40:00Z", 7, 8),
                observation("2026-02-01T00:45:00Z", 8, 9),
                observation("2026-02-01T00:50:00Z", 9, 10));

        List<OpportunityScore> scores = new OpportunityScorerV1().score(observations);

        assertThat(scores).extracting(OpportunityScore::opportunityClass)
                .containsExactly(
                        OpportunityClass.CHOP, OpportunityClass.CHOP, OpportunityClass.CHOP,
                        OpportunityClass.CHOP, OpportunityClass.AMBIGUOUS, OpportunityClass.AMBIGUOUS,
                        OpportunityClass.AMBIGUOUS, OpportunityClass.AMBIGUOUS,
                        OpportunityClass.RUN, OpportunityClass.RUN);
        assertThat(scores).extracting(OpportunityScore::compositeScore)
                .satisfiesExactly(
                        score -> assertThat(score).isCloseTo(0.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(1.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(2.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(3.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(4.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(5.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(6.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(7.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(8.0 / 9.0, offset(0.0000000001)),
                        score -> assertThat(score).isCloseTo(1.0, offset(0.0000000001)));
        assertThat(scores).allSatisfy(score -> {
            assertThat(score.scoreVersion()).isEqualTo("opportunity-v1");
            assertThat(score.labelledObservation()).isIn(observations);
        });
    }

    @Test
    void usesAverageRanksForTiesAndReturnsTimestampOrderedScores() {
        LabelledObservation later = observation("2026-02-01T00:10:00Z", 1, 4);
        LabelledObservation earlier = observation("2026-02-01T00:05:00Z", 0, 4);
        LabelledObservation highest = observation("2026-02-01T00:15:00Z", 2, 8);
        LabelledObservation lowest = observation("2026-02-01T00:20:00Z", 3, 1);

        List<OpportunityScore> scores = new OpportunityScorerV1().score(List.of(later, highest, earlier, lowest));

        assertThat(scores).extracting(score -> score.labelledObservation().state().id().signalTime())
                .containsExactly(
                        earlier.state().id().signalTime(), later.state().id().signalTime(),
                        highest.state().id().signalTime(), lowest.state().id().signalTime());
        assertThat(scores.get(0).compositeScore()).isEqualTo(0.5);
        assertThat(scores.get(1).compositeScore()).isEqualTo(0.5);
        assertThat(scores.get(0).netMfeAtrPercentile()).isEqualTo(0.5);
        assertThat(scores.get(0).rewardToRiskPercentile()).isEqualTo(0.5);
        assertThat(scores.get(0).directionalEfficiencyPercentile()).isEqualTo(0.5);
        assertThat(scores.get(0).timeToMfePercentile()).isEqualTo(0.5);
        assertThat(scores.get(0).twoSidedExcursionPercentile()).isEqualTo(0.5);
    }

    @Test
    void scoresDirectionsAndCalendarMonthsIndependently() {
        LabelledObservation februaryLong = observation("2026-02-28T23:55:00Z", 0, 1);
        LabelledObservation marchLong = observation("2026-03-01T00:00:00Z", 1, 10);
        LabelledObservation marchShort = observation("2026-03-01T00:05:00Z", 2, 5, Direction.SHORT);

        List<OpportunityScore> scores = new OpportunityScorerV1().score(List.of(marchLong, marchShort, februaryLong));

        assertThat(scores).extracting(OpportunityScore::compositeScore).containsOnly(1.0);
        assertThat(scores).extracting(OpportunityScore::opportunityClass).containsOnly(OpportunityClass.RUN);
    }

    @Test
    void usesOnlyAdverseExcursionThatOccurredBeforeMfeForRewardToRisk() {
        LabelledObservation adverseAfterMfe = observation(
                "2026-02-01T00:05:00Z", 0, 2.0, -10.0, false);
        LabelledObservation adverseBeforeMfe = observation(
                "2026-02-01T00:10:00Z", 1, 2.0, -1.0, true);

        List<OpportunityScore> scores = new OpportunityScorerV1().score(List.of(adverseAfterMfe, adverseBeforeMfe));

        assertThat(scores).extracting(OpportunityScore::rewardToRiskPercentile).containsExactly(1.0, 0.0);
    }

    private static LabelledObservation observation(String timestamp, int signalIndex, double quality) {
        return observation(timestamp, signalIndex, quality, Direction.LONG);
    }

    private static LabelledObservation observation(String timestamp, int signalIndex, double quality, Direction direction) {
        return observation(timestamp, signalIndex, quality, -(21.0 - 2.0 * quality), true, direction, quality, 11 - (int) quality);
    }

    private static LabelledObservation observation(
            String timestamp, int signalIndex, double mfeAtr, double maeAtr, boolean maeBeforeMfe) {
        return observation(timestamp, signalIndex, mfeAtr, maeAtr, maeBeforeMfe, Direction.LONG, 0.5, 4);
    }

    private static LabelledObservation observation(
            String timestamp,
            int signalIndex,
            double mfeAtr,
            double maeAtr,
            boolean maeBeforeMfe,
            Direction direction,
            double directionalEfficiency,
            int timeToMfeBars) {
        ObservableState state = new ObservableState(
                new ObservationId("US500", 5, Instant.parse(timestamp), direction),
                signalIndex,
                signalIndex + 1,
                1.0,
                240,
                new FeatureVector(Map.of("observable", 1.0)));
        ForwardPathLabel label = new ForwardPathLabel(
                LabelStatus.COMPLETE_48_BARS,
                100.0,
                Instant.parse(timestamp).plusSeconds(300),
                List.of(),
                mfeAtr,
                mfeAtr,
                maeAtr,
                maeAtr,
                maeBeforeMfe,
                maeBeforeMfe ? ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE : ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE,
                Map.of(),
                Map.of(),
                directionalEfficiency,
                timeToMfeBars,
                1);
        return new LabelledObservation(state, label);
    }
}
