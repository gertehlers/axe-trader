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

class OpportunityZoneBuilderTest {

    @Test
    void joinsRunsSeparatedByAtMostOneNonRunWhenMfeTimesAreAtMostSixBarsApart() {
        OpportunityScore first = score("2026-02-01T00:05:00Z", 0, Direction.LONG, 3, OpportunityClass.RUN);
        OpportunityScore second = score("2026-02-01T00:15:00Z", 2, Direction.LONG, 5, OpportunityClass.RUN);

        List<OpportunityZone> zones = new OpportunityZoneBuilder().build(List.of(second, first));

        assertThat(zones).hasSize(1);
        assertThat(zones.getFirst().scores()).containsExactly(first, second);
        assertThat(new OpportunityZoneBuilder().build(List.of(first, second)).getFirst().zoneId())
                .isEqualTo(zones.getFirst().zoneId());
    }

    @Test
    void startsNewZoneAfterTwoConsecutiveNonRunBars() {
        OpportunityScore first = score("2026-02-01T00:05:00Z", 0, Direction.LONG, 3, OpportunityClass.RUN);
        OpportunityScore nonRunOne = score("2026-02-01T00:10:00Z", 1, Direction.LONG, 3, OpportunityClass.AMBIGUOUS);
        OpportunityScore nonRunTwo = score("2026-02-01T00:15:00Z", 2, Direction.LONG, 3, OpportunityClass.CHOP);
        OpportunityScore second = score("2026-02-01T00:20:00Z", 3, Direction.LONG, 4, OpportunityClass.RUN);

        List<OpportunityZone> zones = new OpportunityZoneBuilder().build(List.of(first, nonRunOne, nonRunTwo, second));

        assertThat(zones).extracting(zone -> zone.scores().size()).containsExactly(1, 1);
    }

    @Test
    void startsNewZoneForOppositeDirectionsOrMfeTimesMoreThanSixBarsApart() {
        OpportunityScore longRun = score("2026-02-01T00:05:00Z", 0, Direction.LONG, 1, OpportunityClass.RUN);
        OpportunityScore shortRun = score("2026-02-01T00:10:00Z", 1, Direction.SHORT, 1, OpportunityClass.RUN);
        OpportunityScore distantMfe = score("2026-02-01T00:15:00Z", 2, Direction.LONG, 9, OpportunityClass.RUN);

        List<OpportunityZone> zones = new OpportunityZoneBuilder().build(List.of(longRun, shortRun, distantMfe));

        assertThat(zones).extracting(OpportunityZone::direction)
                .containsExactly(Direction.LONG, Direction.SHORT, Direction.LONG);
        assertThat(zones).extracting(zone -> zone.scores().size()).containsExactly(1, 1, 1);
    }

    @Test
    void startsNewZoneWhenAdjacentRunsUseDifferentScoreVersions() {
        OpportunityScore first = score("2026-02-01T00:05:00Z", 0, Direction.LONG, 3, OpportunityClass.RUN, "opportunity-v1");
        OpportunityScore second = score("2026-02-01T00:10:00Z", 1, Direction.LONG, 4, OpportunityClass.RUN, "opportunity-v2");

        List<OpportunityZone> zones = new OpportunityZoneBuilder().build(List.of(first, second));

        assertThat(zones).hasSize(2);
        assertThat(zones).extracting(OpportunityZone::scoreVersion)
                .containsExactly("opportunity-v1", "opportunity-v2");
        assertThat(zones).extracting(zone -> zone.scores().size()).containsExactly(1, 1);
    }

    private static OpportunityScore score(
            String timestamp, int signalIndex, Direction direction, int timeToMfeBars, OpportunityClass opportunityClass) {
        return score(timestamp, signalIndex, direction, timeToMfeBars, opportunityClass, "opportunity-v1");
    }

    private static OpportunityScore score(
            String timestamp,
            int signalIndex,
            Direction direction,
            int timeToMfeBars,
            OpportunityClass opportunityClass,
            String scoreVersion) {
        Instant signalTime = Instant.parse(timestamp);
        LabelledObservation observation = new LabelledObservation(
                new ObservableState(
                        new ObservationId("US500", 5, signalTime, direction),
                        signalIndex,
                        signalIndex + 1,
                        1.0,
                        240,
                        new FeatureVector(Map.of("observable", 1.0))),
                new ForwardPathLabel(
                        LabelStatus.COMPLETE_48_BARS,
                        100.0,
                        signalTime.plusSeconds(300),
                        List.of(),
                        3.0,
                        3.0,
                        -1.0,
                        -1.0,
                        true,
                        ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE,
                        Map.of(),
                        Map.of(),
                        0.8,
                        timeToMfeBars,
                        1));
        return new OpportunityScore(observation, 0.9, 0.9, 0.9, 0.9, 0.9, opportunityClass, scoreVersion);
    }
}
