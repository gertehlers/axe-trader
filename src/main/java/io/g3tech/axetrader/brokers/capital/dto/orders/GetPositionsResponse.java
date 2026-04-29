package io.g3tech.axetrader.brokers.capital.dto.orders;

import io.g3tech.axetrader.brokers.capital.dto.positions.PositionItem;
import java.util.List;

public record GetPositionsResponse(
        List<PositionItem> positions
) {
}
