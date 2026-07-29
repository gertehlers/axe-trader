package io.g3tech.axetrader.backtest.runner;

import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared conservative intrabar tier-walk for runner and discovery exits. */
public final class TieredExitEngine {

    private TieredExitEngine() {
    }

    public static Outcome tieredExit(
            BarSeries series, Direction direction, int entryIndex, double entryPrice, double stopDist,
            List<TierLevel> tiers, Ratchet ratchet, int maxHoldingBars) {
        Objects.requireNonNull(series, "series");
        return walk(index -> series.getBar(index), series.getEndIndex(), ExitReason.END, direction, entryIndex,
                entryPrice, stopDist, tiers, ratchet, maxHoldingBars, Set.of());
    }

    /**
     * Side-aware overload for discovery. Entry is supplied from {@link MarketSeries#entryPrice}; all
     * targets, stops, dynamic invalidations, and close-outs use the direction's executable exit side.
     */
    public static Outcome tieredExit(
            MarketSeries market, Direction direction, int entryIndex, double entryPrice, double stopDist,
            List<TierLevel> tiers, Ratchet ratchet, int maxHoldingBars, int finalIndex,
            ExitReason finalReason, Set<Integer> invalidationExitIndices) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(finalReason, "finalReason");
        return walk(index -> market.exitBar(direction, index), Math.min(finalIndex, market.mid().getEndIndex()),
                finalReason, direction, entryIndex, entryPrice, stopDist, tiers, ratchet, maxHoldingBars,
                Set.copyOf(Objects.requireNonNull(invalidationExitIndices, "invalidationExitIndices")));
    }

    private static Outcome walk(
            BarAt barAt, int finalIndex, ExitReason finalReason, Direction direction, int entryIndex,
            double entryPrice, double stopDist, List<TierLevel> tiers, Ratchet ratchet, int maxHoldingBars,
            Set<Integer> invalidationExitIndices) {
        tiers = List.copyOf(Objects.requireNonNull(tiers, "tiers"));
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("tieredExit requires at least one tier");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(ratchet, "ratchet");
        boolean isLong = direction == Direction.LONG;
        double stopLevel = isLong ? entryPrice - stopDist : entryPrice + stopDist;
        List<TierFill> fills = new ArrayList<>();
        int nextTier = 0;
        double remaining = 1.0;

        for (int index = entryIndex + 1; index <= finalIndex && nextTier < tiers.size(); index++) {
            Bar bar = barAt.at(index);
            if (invalidationExitIndices.contains(index) && (index < finalIndex || finalReason == ExitReason.END)) {
                fills.add(new TierFill(index, bar.getOpenPrice().doubleValue(), remaining, ExitReason.INVALIDATION));
                return outcome(fills, nextTier);
            }

            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();
            boolean stopHit = isLong ? low <= stopLevel : high >= stopLevel;
            if (stopHit) {
                fills.add(new TierFill(index, stopLevel, remaining, ExitReason.STOP));
                return outcome(fills, nextTier);
            }

            while (nextTier < tiers.size()) {
                TierLevel tier = tiers.get(nextTier);
                double level = isLong ? entryPrice + tier.targetDist() : entryPrice - tier.targetDist();
                boolean tierHit = isLong ? high >= level : low <= level;
                if (!tierHit) {
                    break;
                }
                fills.add(new TierFill(index, level, tier.fraction(), ExitReason.TARGET));
                remaining -= tier.fraction();
                nextTier++;
            }
            if (nextTier >= tiers.size()) {
                return outcome(fills, nextTier);
            }

            stopLevel = ratchetedStop(ratchet, nextTier, isLong, entryPrice, stopLevel, tiers);
            if (index == finalIndex && finalReason != ExitReason.END) {
                fills.add(new TierFill(index, bar.getClosePrice().doubleValue(), remaining, finalReason));
                return outcome(fills, nextTier);
            }
            if (maxHoldingBars > 0 && index - entryIndex >= maxHoldingBars) {
                fills.add(new TierFill(index, bar.getClosePrice().doubleValue(), remaining, ExitReason.TIME));
                return outcome(fills, nextTier);
            }
        }

        if (remaining > 0.0) {
            Bar bar = barAt.at(finalIndex);
            fills.add(new TierFill(finalIndex, bar.getClosePrice().doubleValue(), remaining, finalReason));
        }
        return outcome(fills, nextTier);
    }

    private static double ratchetedStop(
            Ratchet ratchet, int tiersFilled, boolean isLong, double entryPrice,
            double currentStop, List<TierLevel> tiers) {
        return switch (ratchet) {
            case NONE -> currentStop;
            case BREAKEVEN_AFTER_T1 -> {
                if (tiersFilled >= 2) {
                    double t1 = tiers.getFirst().targetDist();
                    yield isLong ? entryPrice + t1 : entryPrice - t1;
                }
                yield tiersFilled >= 1 ? entryPrice : currentStop;
            }
            case LAGGED -> tiersFilled >= 2 ? entryPrice : currentStop;
        };
    }

    private static Outcome outcome(List<TierFill> fills, int tiersFilled) {
        return new Outcome(List.copyOf(fills), tiersFilled, tiersFilled >= 1);
    }

    private interface BarAt {
        Bar at(int index);
    }

    public record TierLevel(double fraction, double targetDist) {
    }

    public record TierFill(int index, double price, double fraction, ExitReason reason) {
    }

    public record Outcome(List<TierFill> fills, int tiersFilled, boolean hitT1) {
        public int index() {
            return fills.getLast().index();
        }

        public double weightedPrice() {
            return fills.stream().mapToDouble(fill -> fill.fraction() * fill.price()).sum();
        }

        public ExitReason finalReason() {
            return fills.getLast().reason();
        }
    }
}
