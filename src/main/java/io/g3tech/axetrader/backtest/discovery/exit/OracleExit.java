package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.series.MarketSeries;

import java.util.Objects;

/** Hindsight-only per-path upper bound; deliberately separate from executable policies. */
public record OracleExit(int entryIndex, double entryPrice, int exitIndex, double exitPrice, double netPnl) {

    public static OracleExit best(ObservableState entry, MarketSeries market, int maxHoldingBars) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(market, "market");
        Direction direction = entry.id().direction();
        int entryIndex = entry.entryIndex();
        double entryPrice = market.entryPrice(direction, entryIndex);
        int last = Math.min(market.mid().getEndIndex(), entryIndex + maxHoldingBars);
        int bestIndex = entryIndex;
        double bestPrice = entryPrice;
        double bestPnl = 0.0;
        for (int index = entryIndex + 1; index <= last; index++) {
            double candidate = direction == Direction.LONG
                    ? market.exitBar(direction, index).getHighPrice().doubleValue()
                    : market.exitBar(direction, index).getLowPrice().doubleValue();
            double pnl = direction == Direction.LONG ? candidate - entryPrice : entryPrice - candidate;
            if (pnl > bestPnl) {
                bestPnl = pnl;
                bestPrice = candidate;
                bestIndex = index;
            }
        }
        return new OracleExit(entryIndex, entryPrice, bestIndex, bestPrice, bestPnl);
    }
}
