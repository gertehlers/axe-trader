package io.g3tech.axetrader.backtest.discovery.validation;

import java.time.YearMonth;
import java.util.Objects;

/** Calendar-month result. Months below the promotion sample threshold remain reportable. */
public record MonthlyResult(YearMonth month, int tradeCount, double netPnl) {

    public static final int PROMOTION_MINIMUM_TRADES = 10;

    public MonthlyResult {
        Objects.requireNonNull(month, "month");
        if (tradeCount < 0 || !Double.isFinite(netPnl)) {
            throw new IllegalArgumentException("monthly result needs a non-negative trade count and finite net P&L");
        }
    }

    public boolean sampled() {
        return tradeCount >= PROMOTION_MINIMUM_TRADES;
    }
}
