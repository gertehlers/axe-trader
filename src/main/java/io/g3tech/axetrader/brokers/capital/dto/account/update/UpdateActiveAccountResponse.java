package io.g3tech.axetrader.brokers.capital.dto.account.update;

public record UpdateActiveAccountResponse(
        boolean dealingEnabled,
        boolean hasActiveDemoAccounts,
        boolean hasActiveLiveAccounts,
        boolean trailingStopsEnabled
) {
}
