package io.g3tech.axetrader.backtest.strategy;

import org.ta4j.core.Strategy;

import java.util.List;

/**
 * The confluence output for one configuration: the bullish strategy (run as LONG) and the
 * bearish strategy (run as SHORT), plus the named {@link PillarVote}s behind each so the
 * runner can record <em>why</em> a trade was entered.
 */
public record ConfluenceStrategies(
        Strategy longStrategy,
        Strategy shortStrategy,
        List<PillarVote> bullishVotes,
        List<PillarVote> bearishVotes) {
}
