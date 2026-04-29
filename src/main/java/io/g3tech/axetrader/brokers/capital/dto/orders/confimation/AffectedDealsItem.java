package io.g3tech.axetrader.brokers.capital.dto.orders.confimation;

public record AffectedDealsItem(
        String dealId,
        AffectedDealStatus status
) {
}
