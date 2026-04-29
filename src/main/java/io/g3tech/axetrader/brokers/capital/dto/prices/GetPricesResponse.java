package io.g3tech.axetrader.brokers.capital.dto.prices;

import io.g3tech.axetrader.brokers.capital.dto.market.InstrumentType;
import java.util.List;

public record GetPricesResponse(
        List<PricesItem> prices,
        InstrumentType instrumentType,
        Metadata metadata
) {
}
