package io.g3tech.axetrader.brokers.capital.dto.account;

public record AccountItem(
        String accountId,
        String accountName,
        AccountType accountType,
        Balance balance,
        String currency,
        boolean preferred,
        Status status
) {
}
