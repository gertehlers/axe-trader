package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ObservationBatch;
import io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.session.TradingSessionCalendar;
import io.g3tech.axetrader.backtest.discovery.session.UnknownSessionBoundaryException;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.EntryFeatureExtractor;
import io.g3tech.axetrader.backtest.runner.TradeFeatures;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import io.g3tech.axetrader.backtest.strategy.ConfluenceStrategies;
import io.g3tech.axetrader.backtest.strategy.PillarVote;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Extracts the information observable after one completed signal bar.
 *
 * <p>This class never traverses beyond {@code signalIndex}. It emits one LONG and one SHORT state,
 * or one explicitly counted exclusion for each of those bar-direction candidates.
 */
@Component
public final class ObservableStateExtractor {

    private static final int[] LAGS = {1, 2, 3, 6, 12};
    private static final int MAX_LAG = 12;
    private static final int ATR_PERCENTILE_WINDOW = 100;
    private static final int TREND_SLOPE_LOOKBACK = 10;
    private static final List<String> PILLARS = List.of(
            "rsi_bb", "candle", "support_resistance", "structure", "volume_trend");

    private final EntryFeatureExtractor entryFeatures;

    public ObservableStateExtractor(EntryFeatureExtractor entryFeatures) {
        this.entryFeatures = Objects.requireNonNull(entryFeatures, "entryFeatures");
    }

    public ObservationBatch extract(
            String instrument,
            int timeframeMinutes,
            MarketSeries market,
            IndicatorBundle indicators,
            ConfluenceStrategies strategies,
            BacktestProperties.Strategy config,
            TradingSessionCalendar calendar,
            int signalIndex) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(indicators, "indicators");
        Objects.requireNonNull(strategies, "strategies");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(calendar, "calendar");

        BarSeries mid = market.mid();
        if (signalIndex < mid.getBeginIndex() || signalIndex > mid.getEndIndex()) {
            throw new IndexOutOfBoundsException("signalIndex outside market series: " + signalIndex);
        }
        Instant signalTime = mid.getBar(signalIndex).getEndTime();
        ObservationId longId = new ObservationId(
                instrument, timeframeMinutes, signalTime, Direction.LONG);
        ObservationId shortId = new ObservationId(
                instrument, timeframeMinutes, signalTime, Direction.SHORT);
        List<ObservationId> ids = List.of(longId, shortId);
        int entryIndex = signalIndex + 1;

        if (!hasBar(market.mid(), entryIndex)
                || !hasBar(market.bid(), entryIndex)
                || !hasBar(market.ask(), entryIndex)) {
            return exclusions(ids, signalIndex, ObservationExclusion.Reason.NO_EXECUTABLE_NEXT_BAR,
                    "No executable bid/ask bar at entryIndex " + entryIndex);
        }

        int availableHistory = signalIndex - mid.getBeginIndex();
        int requiredHistory = requiredHistory(config);
        if (availableHistory < requiredHistory) {
            return exclusions(ids, signalIndex, ObservationExclusion.Reason.INDICATOR_WARMUP,
                    "Requires " + requiredHistory + " completed prior indices; found " + availableHistory);
        }

        String timelineIssue = timelineIssue(
                market, calendar, signalIndex - requiredHistory, entryIndex, timeframeMinutes);
        if (timelineIssue != null) {
            return exclusions(ids, signalIndex, ObservationExclusion.Reason.UNKNOWN_SESSION, timelineIssue);
        }

        int minutesToClose;
        try {
            minutesToClose = calendar.minutesToClose(signalTime);
        } catch (UnknownSessionBoundaryException exception) {
            return exclusions(ids, signalIndex, ObservationExclusion.Reason.UNKNOWN_SESSION,
                    exception.getMessage());
        }

        try {
            ObservableState longState = state(
                    longId, signalIndex, entryIndex, minutesToClose,
                    market.mid(), indicators, strategies.bullishVotes(), config);
            ObservableState shortState = state(
                    shortId, signalIndex, entryIndex, minutesToClose,
                    market.mid(), indicators, strategies.bearishVotes(), config);
            return new ObservationBatch(List.of(longState, shortState), List.of());
        } catch (NonFiniteFeatureException exception) {
            return exclusions(ids, signalIndex, ObservationExclusion.Reason.NON_FINITE_FEATURE,
                    exception.getMessage());
        }
    }

    private ObservableState state(
            ObservationId id,
            int signalIndex,
            int entryIndex,
            int minutesToClose,
            BarSeries series,
            IndicatorBundle indicators,
            List<PillarVote> votes,
            BacktestProperties.Strategy config) {
        int confluenceScore = confluenceScore(votes, signalIndex);
        TradeFeatures current = entryFeatures.at(
                series, indicators, config, signalIndex, confluenceScore);
        Map<String, Double> values = featureValues(
                series, indicators, config, votes, signalIndex, minutesToClose, current);
        FeatureVector vector;
        try {
            vector = new FeatureVector(values);
        } catch (IllegalArgumentException exception) {
            throw new NonFiniteFeatureException(exception.getMessage(), exception);
        }
        if (!Double.isFinite(current.atr())) {
            throw new NonFiniteFeatureException("entry ATR must be finite");
        }
        return new ObservableState(
                id, signalIndex, entryIndex, current.atr(), minutesToClose, vector);
    }

    private Map<String, Double> featureValues(
            BarSeries series,
            IndicatorBundle indicators,
            BacktestProperties.Strategy config,
            List<PillarVote> votes,
            int signalIndex,
            int minutesToClose,
            TradeFeatures current) {
        TreeMap<String, Double> values = new TreeMap<>();
        Map<Integer, TradeFeatures> lagged = new LinkedHashMap<>();
        for (int lag : LAGS) {
            int index = signalIndex - lag;
            lagged.put(lag, entryFeatures.at(
                    series, indicators, config, index, confluenceScore(votes, index)));
        }

        putBar(values, series.getBar(signalIndex), current.atr(), 0);
        for (int lag : LAGS) {
            TradeFeatures past = lagged.get(lag);
            putBar(values, series.getBar(signalIndex - lag), past.atr(), lag);
            values.put("return." + lag, returnBetween(series, signalIndex, lag));
        }

        values.put("rsi", current.rsi());
        values.put("rsi.oversold_distance", current.rsi() - config.getRsiOversold());
        values.put("rsi.overbought_distance", config.getRsiOverbought() - current.rsi());
        putCurrentAndLags(values, "bb.lower.distance_atr", current.distToBbLowerAtr(),
                lagged, TradeFeatures::distToBbLowerAtr);
        putCurrentAndLags(values, "bb.upper.distance_atr", current.distToBbUpperAtr(),
                lagged, TradeFeatures::distToBbUpperAtr);
        putCurrentAndLags(values, "support.distance_atr", current.distToSupportAtr(),
                lagged, TradeFeatures::distToSupportAtr);
        putCurrentAndLags(values, "resistance.distance_atr", current.distToResistanceAtr(),
                lagged, TradeFeatures::distToResistanceAtr);
        putCurrentAndLags(values, "trend.slope_atr", current.trendSlopeAtr(),
                lagged, TradeFeatures::trendSlopeAtr);
        putCurrentAndLags(values, "atr", current.atr(), lagged, TradeFeatures::atr);
        putCurrentAndLags(values, "atr.percentile", current.atrPercentile(),
                lagged, TradeFeatures::atrPercentile);
        putCurrentAndLags(values, "volume.ratio", current.volumeRatio(),
                lagged, TradeFeatures::volumeRatio);

        values.put("ema.distance_atr",
                emaDistance(indicators, signalIndex, current.atr(), false));
        if (indicators.trendEma != null) {
            values.put("trend_ema.distance_atr", current.distToTrendEmaAtr());
        }

        for (int lag : LAGS) {
            TradeFeatures past = lagged.get(lag);
            values.put("rsi.delta." + lag, current.rsi() - past.rsi());
            values.put("atr.change." + lag, relativeChange(current.atr(), past.atr()));
            values.put("volatility.expanding." + lag, bool(current.atr() > past.atr()));
            values.put("volatility.contracting." + lag, bool(current.atr() < past.atr()));
            values.put("volume.change." + lag,
                    relativeChange(volume(indicators, signalIndex), volume(indicators, signalIndex - lag)));
            values.put("price_volume.agreement." + lag,
                    agreement(returnBetween(series, signalIndex, lag),
                            relativeChange(volume(indicators, signalIndex), volume(indicators, signalIndex - lag))));
            values.put("ema.distance_atr.lag." + lag,
                    emaDistance(indicators, signalIndex - lag, past.atr(), false));
            if (indicators.trendEma != null) {
                values.put("trend_ema.distance_atr.lag." + lag, past.distToTrendEmaAtr());
            }
        }

        values.put("trend.acceleration_atr",
                current.trendSlopeAtr() - entryFeatures.at(
                        series, indicators, config, signalIndex - 1,
                        confluenceScore(votes, signalIndex - 1)).trendSlopeAtr());
        for (int lag : LAGS) {
            int index = signalIndex - lag;
            double slope = lagged.get(lag).trendSlopeAtr();
            double priorSlope = entryFeatures.at(
                    series, indicators, config, index - 1,
                    confluenceScore(votes, index - 1)).trendSlopeAtr();
            values.put("trend.acceleration_atr.lag." + lag, slope - priorSlope);
        }

        putCandlePatterns(values, indicators, signalIndex);
        putStructureDistances(values, indicators, config, signalIndex, current.atr());
        putPillars(values, votes, signalIndex);
        values.put("confluence.score", (double) confluenceScore(votes, signalIndex));
        for (int lag : LAGS) {
            double pastScore = confluenceScore(votes, signalIndex - lag);
            values.put("confluence.score.lag." + lag, pastScore);
            values.put("confluence.score.delta." + lag,
                    confluenceScore(votes, signalIndex) - pastScore);
        }

        values.put("hour_utc", (double) current.hourUtc());
        values.put("day_of_week", (double) current.dayOfWeek());
        values.put("minutes_to_trading_close", (double) minutesToClose);
        return values;
    }

    private static void putBar(
            Map<String, Double> values, Bar bar, double atr, int lag) {
        String suffix = lag == 0 ? "" : ".lag." + lag;
        double open = bar.getOpenPrice().doubleValue();
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();
        values.put("price.open" + suffix, open);
        values.put("price.high" + suffix, high);
        values.put("price.low" + suffix, low);
        values.put("price.close" + suffix, close);
        values.put("volume" + suffix, bar.getVolume().doubleValue());
        values.put("candle.body_atr" + suffix, Math.abs(close - open) / atr);
        values.put("candle.range_atr" + suffix, (high - low) / atr);
        values.put("candle.upper_wick_atr" + suffix, (high - Math.max(open, close)) / atr);
        values.put("candle.lower_wick_atr" + suffix, (Math.min(open, close) - low) / atr);
        values.put("candle.direction" + suffix, Math.signum(close - open));
    }

    private static void putCurrentAndLags(
            Map<String, Double> values,
            String name,
            double current,
            Map<Integer, TradeFeatures> lagged,
            TradeFeatureValue accessor) {
        values.put(name, current);
        lagged.forEach((lag, features) ->
                values.put(name + ".lag." + lag, accessor.value(features)));
    }

    private static void putCandlePatterns(
            Map<String, Double> values, IndicatorBundle indicators, int index) {
        values.put("candle.bullish_engulfing", bool(indicators.bullishEngulfing.getValue(index)));
        values.put("candle.bearish_engulfing", bool(indicators.bearishEngulfing.getValue(index)));
        values.put("candle.bullish_harami", bool(indicators.bullishHarami.getValue(index)));
        values.put("candle.bearish_harami", bool(indicators.bearishHarami.getValue(index)));
        values.put("candle.hammer", bool(indicators.hammer.getValue(index)));
        values.put("candle.shooting_star", bool(indicators.shootingStar.getValue(index)));
    }

    private static void putStructureDistances(
            Map<String, Double> values,
            IndicatorBundle indicators,
            BacktestProperties.Strategy config,
            int index,
            double atr) {
        int lookback = Math.max(1, config.getSwingLookbackBars());
        int start = Math.max(indicators.series.getBeginIndex(), index - lookback);
        double priorLow = Double.MAX_VALUE;
        double priorHigh = -Double.MAX_VALUE;
        for (int cursor = start; cursor < index; cursor++) {
            double close = indicators.closePrice.getValue(cursor).doubleValue();
            priorLow = Math.min(priorLow, close);
            priorHigh = Math.max(priorHigh, close);
        }
        double close = indicators.closePrice.getValue(index).doubleValue();
        values.put("structure.prior_high.distance_atr", (priorHigh - close) / atr);
        values.put("structure.prior_low.distance_atr", (close - priorLow) / atr);
    }

    private static void putPillars(
            Map<String, Double> values, List<PillarVote> votes, int index) {
        Map<String, PillarVote> byName = new LinkedHashMap<>();
        for (PillarVote vote : votes) {
            byName.put(pillarName(vote.name()), vote);
        }
        for (String pillar : PILLARS) {
            PillarVote vote = byName.get(pillar);
            boolean current = satisfied(vote, index);
            boolean prior = satisfied(vote, index - 1);
            String prefix = "pillar." + pillar + ".";
            values.put(prefix + "active", bool(current));
            values.put(prefix + "activated", bool(current && !prior));
            values.put(prefix + "deactivated", bool(!current && prior));
            int persistence = 0;
            for (int cursor = index - 2; cursor <= index; cursor++) {
                if (satisfied(vote, cursor)) {
                    persistence++;
                }
            }
            values.put(prefix + "persistence.3", (double) persistence);
        }
    }

    private static double emaDistance(
            IndicatorBundle indicators, int index, double atr, boolean trend) {
        var ema = trend ? indicators.trendEma : indicators.ema;
        double close = indicators.closePrice.getValue(index).doubleValue();
        return (close - ema.getValue(index).doubleValue()) / atr;
    }

    private static double returnBetween(BarSeries series, int index, int lag) {
        double current = series.getBar(index).getClosePrice().doubleValue();
        double previous = series.getBar(index - lag).getClosePrice().doubleValue();
        return relativeChange(current, previous);
    }

    private static double volume(IndicatorBundle indicators, int index) {
        return indicators.volume.getValue(index).doubleValue();
    }

    private static double relativeChange(double current, double previous) {
        return previous == 0.0 ? Double.NaN : current / previous - 1.0;
    }

    private static double agreement(double priceReturn, double volumeChange) {
        if (priceReturn == 0.0 || volumeChange == 0.0) {
            return 0.0;
        }
        return bool(Math.signum(priceReturn) == Math.signum(volumeChange));
    }

    private static int confluenceScore(List<PillarVote> votes, int index) {
        int score = 0;
        for (PillarVote vote : votes) {
            if (vote.rule().isSatisfied(index)) {
                score++;
            }
        }
        return score;
    }

    private static boolean satisfied(PillarVote vote, int index) {
        return vote != null && vote.rule().isSatisfied(index);
    }

    private static String pillarName(String name) {
        return switch (name) {
            case "RSI+BB" -> "rsi_bb";
            case "Candle" -> "candle";
            case "S/R" -> "support_resistance";
            case "Structure" -> "structure";
            case "Vol+Trend" -> "volume_trend";
            default -> name.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_|_$", "");
        };
    }

    private static int requiredHistory(BacktestProperties.Strategy config) {
        int largestIndicatorPeriod = Math.max(
                Math.max(Math.max(config.getRsiPeriod(), config.getRsiSmoothPeriod()),
                        Math.max(config.getBbPeriod(), config.getEmaPeriod())),
                Math.max(Math.max(config.getAtrPeriod(), config.getVolumeSmaPeriod()),
                        config.getTrendEmaPeriod()));
        int stableLaggedTrend = Math.max(1, largestIndicatorPeriod)
                + MAX_LAG + TREND_SLOPE_LOOKBACK + 1;
        int laggedAtrPercentile = Math.max(1, config.getAtrPeriod())
                + ATR_PERCENTILE_WINDOW - 1 + MAX_LAG;
        int laggedSwing = Math.max(1, config.getSwingLookbackBars()) - 1 + MAX_LAG;
        return Math.max(stableLaggedTrend, Math.max(laggedAtrPercentile, laggedSwing));
    }

    private static String timelineIssue(
            MarketSeries market,
            TradingSessionCalendar calendar,
            int firstIndex,
            int lastIndex,
            int timeframeMinutes) {
        if (timeframeMinutes <= 0) {
            return "Timeframe must be positive: " + timeframeMinutes;
        }
        Duration timeframe = Duration.ofMinutes(timeframeMinutes);
        Instant previousTime = null;
        for (int index = firstIndex; index <= lastIndex; index++) {
            if (!hasBar(market.mid(), index) || !hasBar(market.bid(), index) || !hasBar(market.ask(), index)) {
                return "Missing market bar at index " + index;
            }
            Bar mid = market.mid().getBar(index);
            Bar bid = market.bid().getBar(index);
            Bar ask = market.ask().getBar(index);
            if (!isAligned(mid, bid, timeframe) || !isAligned(mid, ask, timeframe)) {
                return "Unaligned mid/bid/ask bar at index " + index;
            }
            Instant currentTime = mid.getEndTime();
            if (previousTime != null && !currentTime.equals(previousTime.plus(timeframe))
                    && !isKnownSessionBoundary(calendar, previousTime, currentTime)) {
                return "Unexplained data gap between " + previousTime + " and " + currentTime;
            }
            previousTime = currentTime;
        }
        return null;
    }

    private static boolean isAligned(Bar expected, Bar actual, Duration timeframe) {
        return expected.getTimePeriod().equals(timeframe)
                && actual.getTimePeriod().equals(timeframe)
                && expected.getBeginTime().equals(actual.getBeginTime())
                && expected.getEndTime().equals(actual.getEndTime());
    }

    private static boolean isKnownSessionBoundary(
            TradingSessionCalendar calendar, Instant finalExecutableBar, Instant nextOpen) {
        return calendar.boundaryAfter(finalExecutableBar)
                .filter(boundary -> boundary.finalExecutableBar().equals(finalExecutableBar)
                        && boundary.nextOpen().equals(nextOpen))
                .isPresent();
    }

    private static boolean hasBar(BarSeries series, int index) {
        return index >= series.getBeginIndex() && index <= series.getEndIndex();
    }

    private static ObservationBatch exclusions(
            List<ObservationId> ids,
            int signalIndex,
            ObservationExclusion.Reason reason,
            String detail) {
        return new ObservationBatch(List.of(), ids.stream()
                .map(id -> new ObservationExclusion(id, signalIndex, reason, detail))
                .toList());
    }

    private static double bool(boolean value) {
        return value ? 1.0 : 0.0;
    }

    @FunctionalInterface
    private interface TradeFeatureValue {
        double value(TradeFeatures features);
    }

    private static final class NonFiniteFeatureException extends RuntimeException {
        private NonFiniteFeatureException(String message) {
            super(message);
        }

        private NonFiniteFeatureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
