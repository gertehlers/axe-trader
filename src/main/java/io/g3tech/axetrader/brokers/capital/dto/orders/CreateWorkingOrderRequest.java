package io.g3tech.axetrader.brokers.capital.dto.orders;

import java.math.BigDecimal;

public record CreateWorkingOrderRequest(
        String dealReference,
        String epic,
        String expiry,
        Direction direction,
        BigDecimal size,
        BigDecimal level,
        Type type,
        String currencyCode,
        TimeInForce timeInForce,
        String goodTillDate,
        Boolean guaranteedStop,
        Boolean forceOpen,
        BigDecimal stopLevel,
        BigDecimal stopDistance,
        BigDecimal profitLevel,
        BigDecimal profitDistance
) {
}
