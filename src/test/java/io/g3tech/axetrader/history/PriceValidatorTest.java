package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PriceValidatorTest {

    private final PriceValidator validator = new PriceValidator();

    @Test
    void rejectsNonPositiveValues() {
        assertThat(validator.validate(priceWithOpenBid(BigDecimal.ZERO)))
                .contains(PriceValidationFailure.OPEN_BID_NOT_POSITIVE);
    }

    @Test
    void recordsEveryFailureOnTheSamePrice() {
        ImportedPrice invalid = new ImportedPrice(
                Instant.parse("2024-01-01T00:01:00Z"),
                BigDecimal.ZERO, new BigDecimal("1"),
                new BigDecimal("3"), new BigDecimal("2"),
                new BigDecimal("-1"), new BigDecimal("2"),
                new BigDecimal("3"), new BigDecimal("2"), 10L);

        assertThat(validator.validate(invalid)).containsExactlyInAnyOrderElementsOf(Set.of(
                PriceValidationFailure.OPEN_BID_NOT_POSITIVE,
                PriceValidationFailure.HIGH_BID_ABOVE_ASK,
                PriceValidationFailure.LOW_BID_NOT_POSITIVE,
                PriceValidationFailure.CLOSE_BID_ABOVE_ASK));
    }

    private static ImportedPrice priceWithOpenBid(BigDecimal openBid) {
        return new ImportedPrice(
                Instant.parse("2024-01-01T00:01:00Z"),
                openBid, new BigDecimal("4800.2"),
                new BigDecimal("4801.1"), new BigDecimal("4801.2"),
                new BigDecimal("4799.1"), new BigDecimal("4799.2"),
                new BigDecimal("4800.1"), new BigDecimal("4800.2"), 123L);
    }
}
