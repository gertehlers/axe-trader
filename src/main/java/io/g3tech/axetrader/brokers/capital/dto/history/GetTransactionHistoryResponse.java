package io.g3tech.axetrader.brokers.capital.dto.history;

import java.util.List;

public record GetTransactionHistoryResponse(
        List<TransactionsItem> transactions
) {
}
