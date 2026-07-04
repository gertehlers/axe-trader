package io.g3tech.axetrader.backtest.runner;

/**
 * The entry feature vector for one trade, measured at the <em>signal bar</em> (the bar the votes
 * agreed on; ta4j fills on the next bar) using only backward-looking data — no look-ahead. This is
 * what lets losers be clustered by cause rather than outcome
 * (see {@code docs/observability-and-exits-design.md}).
 *
 * <p>Distances are in ATR units so they're comparable across volatility regimes.
 * {@code distToTrendEmaAtr} is {@code null} when the trend-EMA gate is disabled.
 */
public record TradeFeatures(
        double rsi,
        double distToBbLowerAtr,
        double distToBbUpperAtr,
        double distToSupportAtr,
        double distToResistanceAtr,
        Double distToTrendEmaAtr,
        double trendSlopeAtr,
        double atr,
        double atrPercentile,
        double volumeRatio,
        int hourUtc,
        int dayOfWeek,
        int confluenceScore) {
}
