package io.g3tech.axetrader.brokers.capital.dto.market.details;

public record MinDealSize(
        DealingRuleUnit unit,
        Double value
) {
}
