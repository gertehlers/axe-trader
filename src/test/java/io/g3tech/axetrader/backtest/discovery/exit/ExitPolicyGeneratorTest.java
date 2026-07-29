package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.discovery.model.PathPoint;
import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.runner.Direction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExitPolicyGeneratorTest {

    @Test
    void generatesAtMostTwelvePoliciesFromOnlyTheDeclaredFiniteChoices() {
        CandidateRule rule = CandidateRule.create(Direction.LONG,
                List.of(new RuleClause("trend", RuleClause.Operator.GT, 0)), 3, 1, 1);

        List<ExitPolicy> policies = new ExitPolicyGenerator().generate(rule, List.of(
                observation(1.0, -.25), observation(2.0, -.5), observation(3.0, -1), observation(4.0, -2)));

        assertThat(policies).hasSizeLessThanOrEqualTo(12);
        assertThat(policies).allSatisfy(policy -> {
            assertThat(policy.targetsAtr()).allMatch(target -> target == 1.0 || target == 2.0 || target == 3.0);
            assertThat(policy.fractions()).allMatch(fraction -> fraction == 1.0 || fraction == .5 || fraction == .33
                    || fraction == .67 || fraction == .34 || fraction == .25 || fraction == .35 || fraction == .40);
            assertThat(policy.stop().source()).isIn(ExitPolicy.StopSource.MAE_P50, ExitPolicy.StopSource.MAE_P75);
            assertThat(policy.maxHoldingBars()).isEqualTo(48);
        });
    }

    @Test
    void derivesMaeStopsOnlyFromPathsWhoseMaePrecedesMfe() {
        CandidateRule rule = rule();

        List<ExitPolicy> policies = new ExitPolicyGenerator().generate(rule, List.of(
                observation("2026-01-05T00:05:00Z", 1, -1, true),
                observation("2026-01-05T00:10:00Z", 2, -2, true),
                observation("2026-01-05T00:15:00Z", 3, -100, false)));

        assertThat(policies).isNotEmpty().allSatisfy(policy ->
                assertThat(policy.stop().distanceAtr()).isIn(1.0, 2.0));
    }

    @Test
    void ranksByCumulativePeakToTroughDrawdownRatherThanWorstSingleLoss() {
        ExitPolicy policy = new ExitPolicy(rule(), List.of(new ExitPolicy.Tier(1, 20)),
                new ExitPolicy.Stop(ExitPolicy.StopSource.MAE_P50, 20), Ratchet.NONE, List.of(), 48);

        ExitPolicyGenerator.RankingMetrics metrics = ExitPolicyGenerator.metrics(policy, List.of(
                pnlObservation("2026-01-05T00:05:00Z", 10),
                pnlObservation("2026-01-05T00:10:00Z", -2),
                pnlObservation("2026-01-05T00:15:00Z", -2)));

        assertThat(metrics.netProfit()).isEqualTo(6.0);
        assertThat(metrics.maxDrawdown()).isEqualTo(4.0);
        assertThat(metrics.netProfitOverMaxDrawdown()).isEqualTo(1.5);
    }

    private static LabelledObservation observation(double mfeAtr, double maeAtr) {
        return observation("2026-01-05T00:05:00Z", mfeAtr, maeAtr, true);
    }

    private static CandidateRule rule() {
        return CandidateRule.create(Direction.LONG,
                List.of(new RuleClause("trend", RuleClause.Operator.GT, 0)), 3, 1, 1);
    }

    private static LabelledObservation observation(String signalTime, double mfeAtr, double maeAtr, boolean maeBeforeMfe) {
        ObservableState state = new ObservableState(
                new ObservationId("US500", 5, Instant.parse(signalTime), Direction.LONG),
                0, 1, 1, 240, new FeatureVector(Map.of("trend", 1.0)));
        Instant entryTime = Instant.parse(signalTime).plusSeconds(300);
        List<PathPoint> path = maeBeforeMfe
                ? List.of(
                        new PathPoint(2, entryTime.plusSeconds(300), 100, 100, 100 + maeAtr, 100 + maeAtr),
                        new PathPoint(3, entryTime.plusSeconds(600), 100 + maeAtr, 100 + mfeAtr, 100, 100 + mfeAtr))
                : List.of(
                        new PathPoint(2, entryTime.plusSeconds(300), 100, 100 + mfeAtr, 100, 100 + mfeAtr),
                        new PathPoint(3, entryTime.plusSeconds(600), 100 + mfeAtr, 100 + mfeAtr, 100 + maeAtr, 100 + maeAtr));
        ForwardPathLabel label = new ForwardPathLabel(LabelStatus.COMPLETE_48_BARS, 100,
                entryTime, path, mfeAtr, mfeAtr, maeAtr, maeAtr,
                maeBeforeMfe, maeBeforeMfe ? ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE
                        : ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE,
                Map.of(240, mfeAtr), Map.of(240, mfeAtr), 1, 1, 1);
        return new LabelledObservation(state, label);
    }

    private static LabelledObservation pnlObservation(String signalTime, double pnl) {
        ObservableState state = new ObservableState(
                new ObservationId("US500", 5, Instant.parse(signalTime), Direction.LONG),
                0, 1, 1, 240, new FeatureVector(Map.of("trend", 1.0)));
        Instant entryTime = Instant.parse(signalTime).plusSeconds(300);
        double high = pnl > 0 ? 100 + pnl : 100;
        double low = pnl < 0 ? 100 + pnl : 100;
        ForwardPathLabel label = new ForwardPathLabel(LabelStatus.COMPLETE_48_BARS, 100, entryTime,
                List.of(new PathPoint(2, entryTime.plusSeconds(300), 100, high, low, 100 + pnl)),
                Math.max(pnl, 0), Math.max(pnl, 0), Math.min(pnl, 0), Math.min(pnl, 0), pnl < 0,
                pnl < 0 ? ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE : ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE,
                Map.of(240, pnl), Map.of(240, pnl), 1, 1, 1);
        return new LabelledObservation(state, label);
    }
}
