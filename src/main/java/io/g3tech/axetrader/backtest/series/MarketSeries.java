package io.g3tech.axetrader.backtest.series;

import io.g3tech.axetrader.backtest.runner.Direction;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

public record MarketSeries(BarSeries mid, BarSeries bid, BarSeries ask) {
    public MarketSeries {
        if (mid.getBarCount() != bid.getBarCount() || mid.getBarCount() != ask.getBarCount()) {
            throw new IllegalArgumentException("mid, bid and ask series must have equal bar counts");
        }
    }

    public double entryPrice(Direction direction, int index) {
        Bar bar = direction == Direction.LONG ? ask.getBar(index) : bid.getBar(index);
        return bar.getClosePrice().doubleValue();
    }

    public Bar exitBar(Direction direction, int index) {
        return (direction == Direction.LONG ? bid : ask).getBar(index);
    }
}
