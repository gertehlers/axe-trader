package io.g3tech.axetrader.backtest.discovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryWindowPolicyTest {

    private final DiscoveryWindowPolicy policy = new DiscoveryWindowPolicy();

    @Test
    void permitsDevelopmentWindowsThatDoNotOverlapTheProtectedOosPeriod() {
        assertThatCode(() -> policy.requireDevelopmentWindow(
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-12-31T23:59:59Z"))).doesNotThrowAnyException();

        assertThatCode(() -> policy.requireDevelopmentWindow(
                Instant.parse("2025-12-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"))).doesNotThrowAnyException();

        assertThatCode(() -> policy.requireDevelopmentWindow(
                Instant.parse("2026-05-02T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsDevelopmentWindowsThatOverlapTheProtectedOosPeriod() {
        assertThatThrownBy(() -> policy.requireDevelopmentWindow(
                Instant.parse("2025-12-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected OOS");
    }
}
