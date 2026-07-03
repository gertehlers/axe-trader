package io.g3tech.axetrader.backtest.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.num.Num;

import java.util.List;

/**
 * Confluence "voting" indicator. Given a list of pillar {@link Rule}s, its value at a bar
 * is the number of those rules that are satisfied there.
 *
 * <p>This turns a set of boolean pillar signals (technicals, candlesticks, support/resistance,
 * structure, volume/trend) into a single numeric score, which an
 * {@code OverIndicatorRule(score, threshold - 0.5)} can compare against a required vote count.
 */
public class ConfluenceScoreIndicator extends AbstractIndicator<Num> {

    private final List<Rule> votes;

    public ConfluenceScoreIndicator(BarSeries series, List<Rule> votes) {
        super(series);
        this.votes = List.copyOf(votes);
    }

    @Override
    public Num getValue(int index) {
        int score = 0;
        for (Rule vote : votes) {
            if (vote.isSatisfied(index)) {
                score++;
            }
        }
        return getBarSeries().numFactory().numOf(score);
    }

    @Override
    public int getCountOfUnstableBars() {
        return 0;
    }
}
