package io.g3tech.axetrader.backtest.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryRunnerTest {

    @Test
    void reportsSuccessWhenTheRunCompletes() {
        DiscoveryRunner runner = new DiscoveryRunner(() -> null);

        runner.run(null);

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void reportsFailureWithoutPropagatingWhenTheRunIsRefused() {
        DiscoveryRunner runner = new DiscoveryRunner(() -> {
            throw new IllegalStateException("Discovery window loaded no rows");
        });

        runner.run(null);

        assertThat(runner.getExitCode()).isEqualTo(1);
    }
}
