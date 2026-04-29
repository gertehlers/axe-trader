package io.g3tech.axetrader.brokers.capital.dto.market.details;

import io.g3tech.axetrader.brokers.capital.dto.market.MarketStatus;
import java.math.BigDecimal;

public record Snapshot(
        MarketStatus marketStatus,
        BigDecimal netChange,
        BigDecimal percentageChange,
        String updateTime,
        Integer delayTime,
        BigDecimal bid,
        BigDecimal offer,
        BigDecimal high,
        BigDecimal low,
        BigDecimal binaryOdds,
        Integer decimalPlacesFactor,
        Integer scalingFactor,
        BigDecimal controlledRiskExtraSpread
) {
}
