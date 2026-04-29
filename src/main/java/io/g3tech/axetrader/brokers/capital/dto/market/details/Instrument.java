package io.g3tech.axetrader.brokers.capital.dto.market.details;

import io.g3tech.axetrader.brokers.capital.dto.market.InstrumentType;
import java.math.BigDecimal;
import java.util.List;

public record Instrument(
        String epic,
        String expiry,
        String name,
        Boolean forceOpenAllowed,
        Boolean stopsLimitsAllowed,
        Double lotSize,
        InstrumentUnitType unit,
        InstrumentType type,
        Boolean guaranteedStopAllowed,
        Boolean streamingPricesAvailable,
        String marketId,
        List<CurrenciesItem> currencies,
        Integer sprintMarketsMinimumExpiryTime,
        Integer sprintMarketsMaximumExpiryTime,
        List<MarginDepositBandsItem> marginDepositBands,
        BigDecimal margin,
        SlippageFactor slippageFactor,
        OpeningHours openingHours,
        ExpiryDetails expiryDetails,
        RolloverDetails rolloverDetails,
        String newsCode,
        String chartCode,
        String country,
        String valueOfOnePip,
        String onePipMeans,
        String contractSize,
        List<String> specialInfo
) {
}
