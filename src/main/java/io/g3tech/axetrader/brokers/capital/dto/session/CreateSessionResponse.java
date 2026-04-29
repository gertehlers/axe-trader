package io.g3tech.axetrader.brokers.capital.dto.session;

import java.util.List;

public record CreateSessionResponse(
        AccountInfo accountInfo,
        AccountType accountType,
        List<AccountItem> accounts,
        String clientId,
        String currencyIsoCode,
        String currencySymbol,
        String currentAccountId,
        boolean hasActiveDemoAccounts,
        boolean hasActiveLiveAccounts,
        String streamingHost,
        int timezoneOffset,
        Boolean trailingStopsEnabled
) {
}
