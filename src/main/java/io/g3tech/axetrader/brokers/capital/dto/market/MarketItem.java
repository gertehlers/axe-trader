package io.g3tech.axetrader.brokers.capital.dto.market;

public record MarketItem(
        Integer delayTime,
        String epic,
        Float netChange,
        Integer lotSize,
        String expiry,
        InstrumentType instrumentType,
        String instrumentName,
        Float high,
        Float low,
        Float percentageChange,
        String updateTime,
        String updateTimeUTC,
        Float bid,
        Float offer,
        Boolean otcTradeable,
        Boolean streamingPricesAvailable,
        MarketStatus marketStatus,
        Integer scalingFactor
) {
}
