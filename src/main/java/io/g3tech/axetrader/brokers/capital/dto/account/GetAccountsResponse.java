package io.g3tech.axetrader.brokers.capital.dto.account;

import java.util.List;

public record GetAccountsResponse(
        List<AccountItem> accounts
) {
}
