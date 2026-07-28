package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
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

    private static LabelledObservation observation(double mfeAtr, double maeAtr) {
        ObservableState state = new ObservableState(
                new ObservationId("US500", 5, Instant.parse("2026-01-05T00:05:00Z"), Direction.LONG),
                0, 1, 1, 240, new FeatureVector(Map.of("trend", 1.0)));
        ForwardPathLabel label = new ForwardPathLabel(LabelStatus.COMPLETE_48_BARS, 100,
                Instant.parse("2026-01-05T00:10:00Z"), List.of(), mfeAtr, mfeAtr, maeAtr, maeAtr,
                true, ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE, Map.of(240, mfeAtr), Map.of(240, mfeAtr), 1, 1, 1);
        return new LabelledObservation(state, label);
    }
}
