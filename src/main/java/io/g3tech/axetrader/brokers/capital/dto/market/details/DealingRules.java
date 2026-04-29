package io.g3tech.axetrader.brokers.capital.dto.market.details;

public record DealingRules(
        MinStepDistance minStepDistance,
        MinDealSize minDealSize,
        MinGuaranteedStopDistance minGuaranteedStopDistance,
        MinStopOrProfitDistance minStopOrProfitDistance,
        MaxStopOrProfitDistance maxStopOrProfitDistance,
        MarketOrderPreference marketOrderPreference
) {
}
