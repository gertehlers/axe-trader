package io.g3tech.axetrader.brokers.capital.dto.orders.confimation;

import io.g3tech.axetrader.brokers.capital.dto.orders.Direction;
import java.math.BigDecimal;
import java.util.List;

public record GetDealConfirmationResponse(
        PositionStatus status,
        Reason reason,
        DealStatus dealStatus,
        String epic,
        String expiry,
        String dealReference,
        String dealId,
        List<AffectedDealsItem> affectedDeals,
        Float level,
        Float size,
        Direction direction,
        Float stopLevel,
        Float profitLevel,
        Float stopDistance,
        Float profitDistance,
        Boolean guaranteedStop,
        Boolean trailingStop,
        BigDecimal profit,
        String profitCurrency
) {
}
