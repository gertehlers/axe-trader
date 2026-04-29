package io.g3tech.axetrader.brokers.capital.dto.prices;

public record Allowance(
        Integer remainingAllowance,
        Integer totalAllowance,
        Integer allowanceExpiry
) {
}
