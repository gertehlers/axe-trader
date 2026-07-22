package io.g3tech.axetrader.backtest.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.runner.VolatilityRegime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the shared {@link TradeStatistics} money math. {@code maxDrawdown} and
 * {@code worstQuarterNet} were previously private to {@link DashboardExporter} and are covered there
 * via {@code run.json}; the sweep's success gate now leans on the same class plus the genuinely new
 * {@code netByQuarter} / {@code positiveQuarters} / {@code quarterCount}, so they are pinned here
 * against hand-computed values rather than trusted transitively.
 */
class TradeStatisticsTest {

    /** A trade carrying only the two fields TradeStatistics reads: entry time and raw pnl. */
    private static TradeResult trade(String entryIso, double rawPnl) {
        ZonedDateTime entry = Instant.parse(entryIso).atZone(ZoneOffset.UTC);
        ZonedDateTime exit = entry.plus(Duration.ofMinutes(10));
        return new TradeResult(entry, exit, Direction.LONG, 5000, 5000 + rawPnl, rawPnl, 0,
                VolatilityRegime.NORMAL, rawPnl > 0, ExitReason.TARGET, null, List.of(),
                rawPnl > 0 ? 1 : 0, rawPnl > 0);
    }

    /**
     * Six trades, avgSpread=0.5, entry-ordered net pnl (pnl - avgSpread): +2, +3, -1, -3, +6, -2
     * across three 2025 quarters. Q1 = +5, Q2 = -4, Q3 = +4. This is the same dataset the exporter
     * test uses, so the two stores are provably computing the same thing.
     */
    private static List<TradeResult> threeQuarterTrades() {
        double[] rawPnl = {2.5, 3.5, -0.5, -2.5, 6.5, -1.5};
        String[] entryIso = {
                "2025-01-10T10:00:00Z", "2025-02-10T10:00:00Z",
                "2025-04-10T10:00:00Z", "2025-05-10T10:00:00Z",
                "2025-07-10T10:00:00Z", "2025-08-10T10:00:00Z"
        };
        List<TradeResult> trades = new ArrayList<>();
        for (int i = 0; i < rawPnl.length; i++) {
            trades.add(trade(entryIso[i], rawPnl[i]));
        }
        return trades;
    }

    @Test
    void netByQuarterSumsNetPnlPerUtcQuarterInChronologicalOrder() {
        Map<String, Double> byQuarter = TradeStatistics.netByQuarter(threeQuarterTrades(), 0.5);

        assertThat(byQuarter).containsExactly(
                Map.entry("2025Q1", 5.0),
                Map.entry("2025Q2", -4.0),
                Map.entry("2025Q3", 4.0));
    }

    @Test
    void positiveQuartersCountsOnlyNetPositiveQuarters() {
        // Q1 +5 and Q3 +4 are positive; Q2 -4 is not.
        assertThat(TradeStatistics.positiveQuarters(threeQuarterTrades(), 0.5)).isEqualTo(2);
    }

    @Test
    void quarterCountIsTheNumberOfDistinctQuarters() {
        assertThat(TradeStatistics.quarterCount(threeQuarterTrades(), 0.5)).isEqualTo(3);
    }

    @Test
    void worstQuarterNetIsTheMinimumQuarterSumNotTheWorstTrade() {
        // Worst quarter total is -4.0 (Q2). The single worst trade nets -3.0 -- a "worst trade" bug
        // would report -3.0, so this value distinguishes the two.
        assertThat(TradeStatistics.worstQuarterNet(threeQuarterTrades(), 0.5)).isEqualTo(-4.0);
    }

    @Test
    void maxDrawdownTracksRunningPeakOnTheNetEquityCurveAnchoredAtZero() {
        // Cumulative net curve: 2, 5, 4, 1, 7, 5. Deepest peak-to-trough is 5 -> 1 = 4.0.
        assertThat(TradeStatistics.maxDrawdown(threeQuarterTrades(), 0.5)).isEqualTo(4.0);
    }

    @Test
    void statisticsAreOrderIndependentBecauseTheySortByEntryTimeInternally() {
        List<TradeResult> shuffled = new ArrayList<>(threeQuarterTrades());
        java.util.Collections.reverse(shuffled);

        assertThat(TradeStatistics.maxDrawdown(shuffled, 0.5)).isEqualTo(4.0);
        assertThat(TradeStatistics.worstQuarterNet(shuffled, 0.5)).isEqualTo(-4.0);
        assertThat(TradeStatistics.netByQuarter(shuffled, 0.5)).containsExactly(
                Map.entry("2025Q1", 5.0),
                Map.entry("2025Q2", -4.0),
                Map.entry("2025Q3", 4.0));
    }

    @Test
    void quartersInDifferentYearsAreDistinctBuckets() {
        List<TradeResult> trades = List.of(
                trade("2024-11-10T10:00:00Z", 1.5),   // 2024Q4
                trade("2025-11-10T10:00:00Z", 1.5));  // 2025Q4, same quarter-of-year, different year

        assertThat(TradeStatistics.quarterCount(trades, 0.5)).isEqualTo(2);
        assertThat(TradeStatistics.netByQuarter(trades, 0.5)).containsOnlyKeys("2024Q4", "2025Q4");
    }

    @Test
    void emptyTradesYieldZeroesAndAnEmptyMap() {
        List<TradeResult> none = List.of();
        assertThat(TradeStatistics.maxDrawdown(none, 0.5)).isEqualTo(0.0);
        assertThat(TradeStatistics.worstQuarterNet(none, 0.5)).isEqualTo(0.0);
        assertThat(TradeStatistics.positiveQuarters(none, 0.5)).isEqualTo(0);
        assertThat(TradeStatistics.quarterCount(none, 0.5)).isEqualTo(0);
        assertThat(TradeStatistics.netByQuarter(none, 0.5)).isEmpty();
    }
}
