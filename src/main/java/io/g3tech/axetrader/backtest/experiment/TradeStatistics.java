package io.g3tech.axetrader.backtest.experiment;

import io.g3tech.axetrader.backtest.runner.TradeResult;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared trade-level metrics used by both {@link DashboardExporter} (the {@code run.json} it writes
 * for the dashboard) and the sweep harness's success-gate reporting. Extracted so max drawdown and
 * worst-quarter-net have exactly one definition in the codebase — see
 * {@code docs/superpowers/specs/2026-07-21-tiered-scale-out-exits-design.md} for why that matters:
 * {@code ExperimentStore} independently computes a same-named {@code worst_quarter_net} using a
 * per-quarter MEAN rather than a SUM. That is a known, separately-tracked discrepancy; this class is
 * not it and must not be made to match it.
 */
public final class TradeStatistics {

    private TradeStatistics() {
    }

    /**
     * Largest peak-to-trough decline of the cumulative NET pnl equity curve (points), over trades
     * ordered by entry time. The running peak starts at 0 (before any trade), so a strategy that is
     * underwater from trade one reports a real drawdown instead of 0.
     *
     * <p>Sign convention: returned as a NON-NEGATIVE magnitude (0.0 when the curve only ever makes
     * new highs). The frontend renders this via {@code signed()}, so it displays as e.g. {@code
     * +3.50} — read that as "3.50 points were given back from the best point reached," not as a
     * literal net change.
     */
    public static double maxDrawdown(List<TradeResult> trades, double avgSpread) {
        List<TradeResult> ordered = trades.stream()
                .sorted(Comparator.comparing(TradeResult::entryTime))
                .toList();
        double cumulative = 0;
        double peak = 0; // anchored at 0, not the first trade's result
        double maxDrawdown = 0;
        for (TradeResult t : ordered) {
            cumulative += t.pnl() - avgSpread;
            peak = Math.max(peak, cumulative);
            maxDrawdown = Math.max(maxDrawdown, peak - cumulative);
        }
        return maxDrawdown;
    }

    /**
     * Net pnl summed per UTC calendar quarter (e.g. entries in 2024 Q1 and 2025 Q1 are distinct
     * buckets), keyed like {@code "2025Q2"}. Ordered chronologically by first occurrence.
     */
    public static Map<String, Double> netByQuarter(List<TradeResult> trades, double avgSpread) {
        Map<String, Double> byQuarter = new LinkedHashMap<>();
        List<TradeResult> ordered = trades.stream()
                .sorted(Comparator.comparing(TradeResult::entryTime))
                .toList();
        for (TradeResult t : ordered) {
            ZonedDateTime utc = t.entryTime().withZoneSameInstant(ZoneOffset.UTC);
            int quarter = (utc.getMonthValue() - 1) / 3 + 1;
            String key = utc.getYear() + "Q" + quarter;
            byQuarter.merge(key, t.pnl() - avgSpread, Double::sum);
        }
        return byQuarter;
    }

    /**
     * Worst calendar-quarter total of net pnl, grouped by entry time's UTC year+quarter. Typically
     * negative — that's the natural reading and matches the field name. Zero trades yields 0.0.
     */
    public static double worstQuarterNet(List<TradeResult> trades, double avgSpread) {
        if (trades.isEmpty()) {
            return 0.0;
        }
        return netByQuarter(trades, avgSpread).values().stream()
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.0);
    }

    /** Count of quarters (from {@link #netByQuarter}) whose net pnl total is positive. */
    public static long positiveQuarters(List<TradeResult> trades, double avgSpread) {
        return netByQuarter(trades, avgSpread).values().stream()
                .filter(v -> v > 0)
                .count();
    }

    /** Number of distinct UTC calendar quarters present across the trades. */
    public static int quarterCount(List<TradeResult> trades, double avgSpread) {
        return netByQuarter(trades, avgSpread).size();
    }
}
