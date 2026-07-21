package io.g3tech.axetrader.backtest.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The tier ladder is money-critical config: fractions that silently fail to sum to 1.0 would size
 * every trade wrongly and quietly bias every pnl figure in the sweep. So a bad ladder is a startup
 * error, never a silent renormalisation.
 */
class ExitConfigTest {

    private static BacktestProperties.Strategy.ExitTier tier(double fraction, double target) {
        BacktestProperties.Strategy.ExitTier t = new BacktestProperties.Strategy.ExitTier();
        t.setFraction(fraction);
        t.setTargetAtrMultiple(target);
        return t;
    }

    @Test
    void emptyLadderIsValidAndMeansCurrentBehaviour() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();

        assertThatCode(exit::validate).doesNotThrowAnyException();
        assertThat(exit.getTiers()).isEmpty();
        assertThat(exit.getRatchet()).isEqualTo(Ratchet.NONE);
    }

    @Test
    void fractionsSummingToOneAreAccepted() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(0.3333, 0.75), tier(0.3333, 1.5), tier(0.3334, 3.0)));

        assertThatCode(exit::validate).doesNotThrowAnyException();
    }

    @Test
    void fractionsNotSummingToOneAreRejected() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(0.5, 0.75), tier(0.3, 1.5)));

        assertThatThrownBy(exit::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0.8");
    }

    @Test
    void nonPositiveFractionIsRejected() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(1.0, 0.75), tier(0.0, 1.5)));

        assertThatThrownBy(exit::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void tiersMustBeInAscendingTargetOrder() {
        BacktestProperties.Strategy.Exit exit = new BacktestProperties.Strategy.Exit();
        exit.setTiers(List.of(tier(0.5, 1.5), tier(0.5, 0.75)));

        assertThatThrownBy(exit::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ascending");
    }
}
