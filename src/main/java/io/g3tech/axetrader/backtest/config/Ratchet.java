package io.g3tech.axetrader.backtest.config;

/**
 * What the stop does as scale-out tiers fill. See
 * {@code docs/superpowers/specs/2026-07-21-tiered-scale-out-exits-design.md}.
 *
 * <p>All three are swept rather than assumed — which one suits an instrument is part of its
 * "personality", not a universal truth.
 */
public enum Ratchet {
    /** Stop never moves; stays at the original ATR multiple for the life of the trade. */
    NONE,
    /** After tier 1 fills the stop moves to entry; after tier 2 fills it moves to tier 1's level. */
    BREAKEVEN_AFTER_T1,
    /** Stop holds at the original level until tier 2 fills, then moves to entry. */
    LAGGED
}
