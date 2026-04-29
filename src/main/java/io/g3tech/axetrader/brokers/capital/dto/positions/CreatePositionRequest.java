package io.g3tech.axetrader.brokers.capital.dto.positions;

import io.g3tech.axetrader.brokers.capital.dto.orders.Direction;
import java.math.BigDecimal;

public record CreatePositionRequest(
        Direction direction,
        String epic,
        Boolean guaranteedStop,
        Boolean trailingStop,
        BigDecimal profitAmount,
        BigDecimal profitLevel,
        BigDecimal profitDistance,
        BigDecimal size,
        BigDecimal stopAmount,
        BigDecimal stopDistance,
        BigDecimal stopLevel
) {
}
