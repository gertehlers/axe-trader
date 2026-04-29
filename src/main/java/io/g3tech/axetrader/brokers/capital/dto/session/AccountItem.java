package io.g3tech.axetrader.brokers.capital.dto.session;

public record AccountItem(
        String accountId,
        String accountName,
        Boolean preferred,
        AccountType accountType
) {
}
