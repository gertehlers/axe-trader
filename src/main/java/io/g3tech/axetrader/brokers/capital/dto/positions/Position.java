package io.g3tech.axetrader.brokers.capital.dto.positions;

import io.g3tech.axetrader.brokers.capital.dto.orders.Direction;
import java.math.BigDecimal;

public record Position(
        Float contractSize,
        String createdDate,
        String dealId,
        BigDecimal dealSize,
        Direction direction,
        BigDecimal profitLevel,
        BigDecimal openLevel,
        String currency,
        Boolean guaranteedStop,
        BigDecimal stopLevel,
        BigDecimal trailingStep,
        BigDecimal trailingStopDistance
) {
}
