package io.g3tech.axetrader.backtest.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify Spring can bind a nested tier ladder from YAML/properties into the config model.
 * This is a regression guard: the tier ladder is only ever built programmatically in unit tests
 * so far. A later task promotes a tuned ladder into application.yaml, and if binding fails
 * silently, the promoted config would load as an empty ladder and run the old exit behaviour
 * while appearing correct.
 */
@SpringBootTest(properties = {
        "axe-trader.mode=backtest",
        "axe-trader.backtest.enabled=false",
        "backtest.strategy.exit.tiers[0].fraction=0.4",
        "backtest.strategy.exit.tiers[0].target-atr-multiple=0.75",
        "backtest.strategy.exit.tiers[1].fraction=0.35",
        "backtest.strategy.exit.tiers[1].target-atr-multiple=1.5",
        "backtest.strategy.exit.tiers[2].fraction=0.25",
        "backtest.strategy.exit.tiers[2].target-atr-multiple=3.0",
        "backtest.strategy.exit.ratchet=breakeven-after-t1"
})
class ExitConfigBindingTest {

    @Autowired
    private BacktestProperties backtestProperties;

    @Test
    void tiersBindFromPropertiesInOrder() {
        BacktestProperties.Strategy.Exit exit = backtestProperties.getStrategy().getExit();

        assertThat(exit.getTiers()).hasSize(3);

        // First tier
        assertThat(exit.getTiers().get(0).getFraction()).isEqualTo(0.4);
        assertThat(exit.getTiers().get(0).getTargetAtrMultiple()).isEqualTo(0.75);

        // Second tier
        assertThat(exit.getTiers().get(1).getFraction()).isEqualTo(0.35);
        assertThat(exit.getTiers().get(1).getTargetAtrMultiple()).isEqualTo(1.5);

        // Third tier
        assertThat(exit.getTiers().get(2).getFraction()).isEqualTo(0.25);
        assertThat(exit.getTiers().get(2).getTargetAtrMultiple()).isEqualTo(3.0);
    }

    @Test
    void ratchetEnumBindsFromKebabCase() {
        BacktestProperties.Strategy.Exit exit = backtestProperties.getStrategy().getExit();

        assertThat(exit.getRatchet()).isEqualTo(Ratchet.BREAKEVEN_AFTER_T1);
    }

}
