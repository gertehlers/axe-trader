package io.g3tech.axetrader.backtest.config;

/**
 * Which entry thesis the confluence pillars express. Both modes vote through the same
 * {@code PillarVote}/{@code ConfluenceScoreIndicator} machinery — the difference is what each pillar
 * looks for and, structurally, the win-rate/expectancy shape they produce.
 *
 * <ul>
 *   <li>{@link #MEAN_REVERSION} — buy oversold dips near support (RSI extreme + BB touch + near a
 *       swing low). High win rate (~80%) but wins ≈ losses in size, so it is structurally
 *       break-even after costs on US500 5m (see TODO.md iterations 1–13).</li>
 *   <li>{@link #MOMENTUM} — buy strength/continuation (RSI above midline and rising, break of the
 *       prior swing high, volume thrust, continuation candle) in an up-regime. Lower win rate but
 *       positive skew (small stops, winners trail), aimed at net-positive expectancy (iteration 14).
 *       </li>
 * </ul>
 */
public enum StrategyMode {
    MEAN_REVERSION,
    MOMENTUM
}
