package io.g3tech.axetrader.strategy.backtest.repositories;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InstantConverterTest {

    private final InstantConverter converter = new InstantConverter();

    @Test
    void writesTheCanonicalSecondsFormat() {
        assertThat(converter.convertToDatabaseColumn(Instant.parse("2024-12-04T23:20:00Z")))
                .isEqualTo("2024-12-04T23:20:00Z");
    }

    @Test
    void readsBothTheCanonicalAndTheLegacyShortFormat() {
        assertThat(converter.convertToEntityAttribute("2024-12-04T23:20:00Z"))
                .isEqualTo(Instant.parse("2024-12-04T23:20:00Z"));
        assertThat(converter.convertToEntityAttribute("2024-12-04T23:20Z"))
                .isEqualTo(Instant.parse("2024-12-04T23:20:00Z"));
    }
}
