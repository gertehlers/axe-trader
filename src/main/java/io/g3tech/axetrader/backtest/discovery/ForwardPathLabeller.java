package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
import io.g3tech.axetrader.backtest.discovery.model.PathPoint;
import io.g3tech.axetrader.backtest.discovery.session.SessionBoundary;
import io.g3tech.axetrader.backtest.discovery.session.TradingSessionCalendar;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Produces future-only labels from the shared side-aware next-bar execution convention. */
@Component
public final class ForwardPathLabeller {

    private static final int MAX_HOLDING_BARS = 48;
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final int[] HORIZON_MINUTES = {5, 15, 30, 60, 120, 240};

    public ForwardPathLabel label(
            Direction direction,
            int entryIndex,
            double entryAtr,
            MarketSeries market,
            TradingSessionCalendar calendar) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(calendar, "calendar");
        if (!Double.isFinite(entryAtr)) {
            throw new IllegalArgumentException("entryAtr must be finite");
        }

        if (!hasAlignedBar(market, entryIndex)) {
            return noExecutableNextBar();
        }

        Bar entryBar = market.mid().getBar(entryIndex);
        Instant entryTime = entryBar.getEndTime();
        double entryPrice = market.entryPrice(direction, entryIndex);
        Optional<SessionBoundary> boundary = calendar.boundaryAfter(entryTime);
        if (boundary.isEmpty()) {
            return incomplete(entryPrice, entryTime, List.of(), direction, entryAtr);
        }

        int finalSessionIndex = indexAt(market.mid(), boundary.get().finalExecutableBar());
        if (finalSessionIndex < entryIndex) {
            return incomplete(entryPrice, entryTime, List.of(), direction, entryAtr);
        }
        int finalIndex = Math.min(entryIndex + MAX_HOLDING_BARS, finalSessionIndex);
        List<PathPoint> path = new ArrayList<>();
        Instant previousTime = entryTime;
        for (int index = entryIndex + 1; index <= finalIndex; index++) {
            if (!hasAlignedBar(market, index)) {
                return incomplete(entryPrice, entryTime, path, direction, entryAtr);
            }
            Bar exit = market.exitBar(direction, index);
            if (!exit.getEndTime().equals(previousTime.plus(FIVE_MINUTES))) {
                return incomplete(entryPrice, entryTime, path, direction, entryAtr);
            }
            path.add(new PathPoint(
                    index,
                    exit.getEndTime(),
                    exit.getOpenPrice().doubleValue(),
                    exit.getHighPrice().doubleValue(),
                    exit.getLowPrice().doubleValue(),
                    exit.getClosePrice().doubleValue()));
            previousTime = exit.getEndTime();
        }

        LabelStatus status = finalIndex == entryIndex + MAX_HOLDING_BARS
                ? LabelStatus.COMPLETE_48_BARS
                : LabelStatus.TRADING_CLOSE;
        return completed(status, entryPrice, entryTime, path, direction, entryAtr);
    }

    private static ForwardPathLabel noExecutableNextBar() {
        return new ForwardPathLabel(
                LabelStatus.NO_EXECUTABLE_NEXT_BAR, Double.NaN, null, List.of(),
                0.0, 0.0, 0.0, 0.0, false,
                ForwardPathLabel.ExcursionOrder.SIMULTANEOUS,
                Map.of(), Map.of(), 0.0, 0, 0);
    }

    private static ForwardPathLabel incomplete(
            double entryPrice,
            Instant entryTime,
            List<PathPoint> path,
            Direction direction,
            double atr) {
        return completed(LabelStatus.INCOMPLETE_GAP, entryPrice, entryTime, path, direction, atr);
    }

    private static ForwardPathLabel completed(
            LabelStatus status,
            double entryPrice,
            Instant entryTime,
            List<PathPoint> path,
            Direction direction,
            double atr) {
        double mfe = 0.0;
        double mae = 0.0;
        int mfeTime = 0;
        int maeTime = 0;
        double previousClose = entryPrice;
        double totalMovement = 0.0;
        for (int offset = 0; offset < path.size(); offset++) {
            PathPoint point = path.get(offset);
            double favourable = direction == Direction.LONG
                    ? point.highPrice() - entryPrice : entryPrice - point.lowPrice();
            double adverse = direction == Direction.LONG
                    ? point.lowPrice() - entryPrice : entryPrice - point.highPrice();
            if (favourable > mfe) {
                mfe = favourable;
                mfeTime = offset + 1;
            }
            if (adverse < mae) {
                mae = adverse;
                maeTime = offset + 1;
            }
            totalMovement += Math.abs(point.closePrice() - previousClose);
            previousClose = point.closePrice();
        }
        Map<Integer, Double> horizonPoints = horizonReturns(path, entryPrice, direction);
        Map<Integer, Double> horizonAtr = normalised(horizonPoints, atr);
        double finalReturn = path.isEmpty() ? 0.0 : returnPoints(path.getLast().closePrice(), entryPrice, direction);
        ForwardPathLabel.ExcursionOrder order = excursionOrder(mfeTime, maeTime);
        return new ForwardPathLabel(
                status,
                entryPrice,
                entryTime,
                path,
                mfe,
                normalise(mfe, atr),
                mae,
                normalise(mae, atr),
                maeTime > 0 && (mfeTime == 0 || maeTime < mfeTime),
                order,
                horizonPoints,
                horizonAtr,
                totalMovement == 0.0 ? 0.0 : finalReturn / totalMovement,
                mfeTime,
                maeTime);
    }

    private static Map<Integer, Double> horizonReturns(
            List<PathPoint> path, double entryPrice, Direction direction) {
        Map<Integer, Double> returns = new LinkedHashMap<>();
        for (int minutes : HORIZON_MINUTES) {
            int offset = minutes / 5;
            if (path.size() >= offset) {
                returns.put(minutes, returnPoints(path.get(offset - 1).closePrice(), entryPrice, direction));
            }
        }
        return returns;
    }

    private static Map<Integer, Double> normalised(Map<Integer, Double> values, double atr) {
        Map<Integer, Double> normalised = new LinkedHashMap<>();
        values.forEach((horizon, value) -> normalised.put(horizon, normalise(value, atr)));
        return normalised;
    }

    private static double normalise(double value, double atr) {
        return atr == 0.0 ? 0.0 : value / atr;
    }

    private static double returnPoints(double exitPrice, double entryPrice, Direction direction) {
        return direction == Direction.LONG ? exitPrice - entryPrice : entryPrice - exitPrice;
    }

    private static ForwardPathLabel.ExcursionOrder excursionOrder(int mfeTime, int maeTime) {
        if (mfeTime == maeTime) {
            return ForwardPathLabel.ExcursionOrder.SIMULTANEOUS;
        }
        if (mfeTime == 0) {
            return ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE;
        }
        if (maeTime == 0) {
            return ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE;
        }
        return mfeTime < maeTime
                ? ForwardPathLabel.ExcursionOrder.MFE_THEN_MAE
                : ForwardPathLabel.ExcursionOrder.MAE_THEN_MFE;
    }

    private static int indexAt(BarSeries series, Instant time) {
        for (int index = series.getBeginIndex(); index <= series.getEndIndex(); index++) {
            if (series.getBar(index).getEndTime().equals(time)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean hasAlignedBar(MarketSeries market, int index) {
        if (!hasBar(market.mid(), index) || !hasBar(market.bid(), index) || !hasBar(market.ask(), index)) {
            return false;
        }
        Bar mid = market.mid().getBar(index);
        return aligned(mid, market.bid().getBar(index)) && aligned(mid, market.ask().getBar(index));
    }

    private static boolean aligned(Bar expected, Bar actual) {
        return expected.getTimePeriod().equals(FIVE_MINUTES)
                && actual.getTimePeriod().equals(FIVE_MINUTES)
                && expected.getBeginTime().equals(actual.getBeginTime())
                && expected.getEndTime().equals(actual.getEndTime());
    }

    private static boolean hasBar(BarSeries series, int index) {
        return index >= series.getBeginIndex() && index <= series.getEndIndex();
    }
}
