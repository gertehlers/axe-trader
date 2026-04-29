package io.g3tech.axetrader.brokers.capital.dto.session;

public record AccountInfo(
        Float balance,
        Float deposit,
        Float profitLoss,
        Float available
) {
}
