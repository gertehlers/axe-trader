package io.g3tech.axetrader.backtest.strategy;

import org.ta4j.core.Rule;

/**
 * A single confluence "vote": a short human-readable {@code name} (shown as the entry reason
 * on the chart) paired with the ta4j {@link Rule} that decides whether the pillar agrees at a bar.
 */
public record PillarVote(String name, Rule rule) {
}
