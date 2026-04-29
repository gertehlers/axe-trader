package io.g3tech.axetrader.brokers.capital.dto.orders;

public record WorkingOrdersItem(
        WorkingOrderData workingOrderData,
        MarketData marketData
) {
}
