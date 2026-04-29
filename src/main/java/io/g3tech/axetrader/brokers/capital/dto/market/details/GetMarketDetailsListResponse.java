package io.g3tech.axetrader.brokers.capital.dto.market.details;

import java.util.List;

public record GetMarketDetailsListResponse(
        List<GetMarketDetailsResponse> marketDetails
) {
}
