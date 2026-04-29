package io.g3tech.axetrader.brokers.capital.dto.prices;

import java.math.BigDecimal;

public record LowPrice(
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal lastTraded
) {
}
