package io.g3tech.axetrader.backtest.runner;

/**
 * Why a position closed. Central to clustering losers by cause (see
 * {@code docs/observability-and-exits-design.md}).
 *
 * <p>The confluence exit rule is {@code stopLoss OR stopGain OR timeStop}: stopGain only fires in
 * profit, stopLoss only in loss, timeStop regardless. A position still open at the last bar is
 * force-closed by the backtester ({@link #END}).
 */
public enum ExitReason {
    /** Hit the ATR take-profit (a profitable, non-time, non-end exit). */
    TARGET,
    /** Hit the ATR stop-loss (a losing, non-time, non-end exit). */
    STOP,
    /**
     * Scale-out only: the final tranche exited on its trailing stop (peak − trail·ATR, floored at
     * T1), a profitable exit distinct from a hard {@link #TARGET}. The trade's reason reflects how
     * the <em>last</em> tranche closed, so an early stop before any tier still reads {@link #STOP}.
     */
    TRAIL,
    /** Force-exit after {@code max-holding-bars} with neither stop nor target reached. */
    TIME,
    /** Position still open when the data ran out; closed on the final bar. */
    END
}
