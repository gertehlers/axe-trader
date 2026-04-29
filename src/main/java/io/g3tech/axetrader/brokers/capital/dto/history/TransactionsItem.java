package io.g3tech.axetrader.brokers.capital.dto.history;

public record TransactionsItem(
        String currency,
        String date,
        String dateUtc,
        String instrumentName,
        String reference,
        String size,
        String transactionType
) {
}
