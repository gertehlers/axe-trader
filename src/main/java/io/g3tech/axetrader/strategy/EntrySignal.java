package io.g3tech.axetrader.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record EntrySignal(
        Direction direction,
        Instant candleTime,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        int score,
        MarketBias bias,
        IndicatorSnapshot indicators,
        List<String> reasons
) {

    public EntrySignal {
        reasons = List.copyOf(reasons);
    }
}
