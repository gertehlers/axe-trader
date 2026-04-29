package io.g3tech.axetrader.brokers.capital.dto.positions;

import io.g3tech.axetrader.brokers.capital.dto.orders.MarketData;

public record PositionItem(
        Position position,
        MarketData market
) {
}
