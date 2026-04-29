package io.g3tech.axetrader.brokers.capital.dto.orders;

import java.math.BigDecimal;

public record WorkingOrderData(
        String createdDate,
        String createdDateUTC,
        String currencyCode,
        String dealId,
        Direction direction,
        String epic,
        Boolean dma,
        String goodTillDate,
        String goodTillDateUTC,
        Boolean guaranteedStop,
        BigDecimal profitDistance,
        BigDecimal orderLevel,
        BigDecimal orderSize,
        OrderType orderType,
        BigDecimal stopDistance,
        TimeInForce timeInForce
) {
}
