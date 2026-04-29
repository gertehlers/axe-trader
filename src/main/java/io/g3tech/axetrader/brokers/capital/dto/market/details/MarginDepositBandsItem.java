package io.g3tech.axetrader.brokers.capital.dto.market.details;

import java.math.BigDecimal;

public record MarginDepositBandsItem(
        Integer min,
        Integer max,
        BigDecimal margin
) {
}
