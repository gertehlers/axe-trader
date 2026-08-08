package io.g3tech.axetrader.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AxeTraderModeTest {

    @Test
    void resolvesDiscoveryFromTheCommandLineValue() {
        assertThat(AxeTraderMode.from("discovery")).isEqualTo(AxeTraderMode.DISCOVERY);
    }

    @Test
    void treatsDiscoveryAsAnOfflineModeSoTheLiveMarketPathStaysOff() {
        assertThat(AxeTraderMode.DISCOVERY.isLiveMarketMode()).isFalse();
    }
}
