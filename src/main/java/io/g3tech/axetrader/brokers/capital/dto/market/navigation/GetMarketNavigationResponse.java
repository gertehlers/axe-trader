package io.g3tech.axetrader.brokers.capital.dto.market.navigation;

import io.g3tech.axetrader.brokers.capital.dto.market.MarketItem;
import java.util.List;

public record GetMarketNavigationResponse(
        List<NodeItem> nodes,
        List<MarketItem> markets
) {
}
