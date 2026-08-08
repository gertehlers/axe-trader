package io.g3tech.axetrader;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineModeEnvironmentTest {

    @Test
    void treatsDiscoveryAsHeadlessSoTheProcessExitsWhenTheRunnerFinishes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("axe-trader.mode", "discovery");

        assertThat(OfflineMode.isHeadless(environment)).isTrue();
    }

    @Test
    void treatsHistoryImportAsHeadless() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("axe-trader.history-import.enabled", "true");

        assertThat(OfflineMode.isHeadless(environment)).isTrue();
    }

    @Test
    void leavesMonitorModeAsAWebApplication() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("axe-trader.mode", "monitor");

        assertThat(OfflineMode.isHeadless(environment)).isFalse();
    }

    @Test
    void leavesTheDefaultConfigurationAsAWebApplication() {
        assertThat(OfflineMode.isHeadless(new MockEnvironment())).isFalse();
    }

    @Test
    void isCaseInsensitiveAboutTheModeValue() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("axe-trader.mode", "DISCOVERY");

        assertThat(OfflineMode.isHeadless(environment)).isTrue();
    }
}
