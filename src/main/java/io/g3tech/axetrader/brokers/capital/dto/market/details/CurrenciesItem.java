package io.g3tech.axetrader.brokers.capital.dto.market.details;

public record CurrenciesItem(
        String code,
        String name,
        String symbol,
        Float baseExchangeRate,
        Float exchangeRate,
        Boolean isDefault
) {
}
