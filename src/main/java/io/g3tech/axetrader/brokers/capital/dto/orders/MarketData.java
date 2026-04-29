package io.g3tech.axetrader.brokers.capital.dto.orders;

import io.g3tech.axetrader.brokers.capital.dto.market.InstrumentType;
import io.g3tech.axetrader.brokers.capital.dto.market.MarketStatus;

public record MarketData(
        String instrumentName,
        String expiry,
        String epic,
        InstrumentType instrumentType,
        Float lotSize,
        Float high,
        Float low,
        Float percentageChange,
        Float netChange,
        Float bid,
        Float offer,
        String updateTime,
        Integer delayTime,
        Boolean streamingPricesAvailable,
        MarketStatus marketStatus,
        Integer scalingFactor
) {
}
