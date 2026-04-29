package io.g3tech.axetrader.brokers.capital.dto.account;

public record Balance(
        Float balance,
        Float deposit,
        Float profitLoss,
        Float available
) {
}
