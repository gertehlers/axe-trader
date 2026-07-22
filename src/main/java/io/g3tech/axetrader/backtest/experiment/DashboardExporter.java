package io.g3tech.axetrader.backtest.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.TradeFeatures;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

/**
 * Read-only exporter: turns an in-memory sweep result into the {@code run.json} the D1 push script
 * loads (see {@code dashboard/scripts/push-run.ts}). Adds nothing the engine doesn't already know —
 * it only derives the stop/target geometry and slices the bar window the phone chart needs.
 *
 * <p>Stop/target must mirror {@code BacktestRunner}: distances are ATR multiples measured at entry.
 */
public final class DashboardExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAD = 50; // bars kept before entry index and after exit index

    private DashboardExporter() {
    }

    /** The join key that survives re-runs: {@code instrument|entryTsIso|direction}. */
    public static String signalKey(String instrument, Instant entry, Direction dir) {
        return instrument + "|" + entry.toString() + "|" + dir.name();
    }

    public static void export(
            Path out, String label, String runId, BacktestProperties.Strategy config, String configJson,
            String instrument, int timeframeMin, Instant windowFrom, Instant windowTo,
            double valuePerPoint, double avgSpread, long tradingDays,
            BarSeries series, List<TradeResult> trades) {
        try {
            int count = trades.size();
            // Gross win rate, matching ConfluenceSweepTest.SweepResult.winRate (net pnl is reported
            // separately as net_avg_pnl).
            long wins = trades.stream().filter(t -> t.pnl() > 0).count();
            double winRate = count == 0 ? 0 : (double) wins / count;
            double netAvgPnl = trades.stream().mapToDouble(t -> t.pnl() - avgSpread).average().orElse(0);
            double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
            double tradesPerDay = tradingDays == 0 ? 0 : (double) count / tradingDays;
            double maxDrawdown = TradeStatistics.maxDrawdown(trades, avgSpread);
            double worstQuarterNet = TradeStatistics.worstQuarterNet(trades, avgSpread);

            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode run = root.putObject("run");
            run.put("id", runId);
            run.put("created_at", Instant.now().toString());
            run.put("label", label);
            run.put("config_json", configJson);
            run.put("instrument", instrument);
            run.put("timeframe_min", timeframeMin);
            run.put("window_start", windowFrom.toString());
            run.put("window_end", windowTo.toString());
            run.put("trades_count", count);
            run.put("trades_per_day", tradesPerDay);
            run.put("win_rate", winRate);
            run.put("net_avg_pnl", netAvgPnl);
            run.put("net_avg_pnl_usd", netAvgPnl * valuePerPoint);
            run.put("avg_r", avgR);
            run.put("max_drawdown", maxDrawdown);
            run.put("worst_quarter_net", worstQuarterNet);

            ArrayNode arr = root.putArray("trades");
            for (TradeResult t : trades) {
                arr.add(tradeNode(t, config, instrument, series, avgSpread));
            }
            Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write dashboard run.json to " + out, e);
        }
    }

    private static ObjectNode tradeNode(
            TradeResult t, BacktestProperties.Strategy config, String instrument, BarSeries series,
            double avgSpread) {
        TradeFeatures f = t.features();
        double atr = f == null ? 0 : f.atr();
        double stopDist = config.getStopAtrMultiple() * atr;
        double targetDist = config.getTargetAtrMultiple() * atr;
        boolean isLong = t.direction() == Direction.LONG;
        double stop = isLong ? t.entryPrice() - stopDist : t.entryPrice() + stopDist;
        double target = isLong ? t.entryPrice() + targetDist : t.entryPrice() - targetDist;

        ObjectNode n = MAPPER.createObjectNode();
        n.put("signal_key", signalKey(instrument, t.entryTime().toInstant(), t.direction()));
        n.put("entry_time", t.entryTime().toInstant().toString());
        n.put("exit_time", t.exitTime().toInstant().toString());
        n.put("direction", t.direction().name());
        n.put("entry_price", t.entryPrice());
        n.put("exit_price", t.exitPrice());
        n.put("stop_price", stop);
        n.put("target_price", target);
        n.put("pnl", t.pnl());
        // Genuinely net: raw pnl less the average spread paid to get in and out. This MUST stay
        // consistent with the run-level `net_avg_pnl` above (mean of `pnl - avgSpread`) — the
        // dashboard's equity curve cumulates this column under a "net" heading, and when it held
        // raw pnl the curve sloped upward while the net-expectancy KPI directly above it read
        // negative. That is the close-based-model trap the 2026-07-04 pnl audit already caught
        // once; do not "simplify" this back to t.pnl().
        //
        // `is_win` deliberately stays GROSS (see the win-rate comment above), so a trade can be
        // is_win=1 with net_pnl<0 — that gap is the spread, and it is the point.
        n.put("net_pnl", t.pnl() - avgSpread);
        n.put("r_multiple", t.rMultiple());
        n.put("exit_reason", t.exitReason() == null ? null : t.exitReason().name());
        n.put("is_win", t.isWin() ? 1 : 0);
        if (f != null) {
            n.put("rsi_value", f.rsi());
            if (f.distToTrendEmaAtr() != null) {
                n.put("dist_to_trend_ema_atr", f.distToTrendEmaAtr());
            }
            n.put("atr_value", f.atr());
            n.put("atr_percentile", f.atrPercentile());
            n.put("volume_ratio", f.volumeRatio());
            n.put("hour_utc", f.hourUtc());
            n.put("day_of_week", f.dayOfWeek());
            n.put("confluence_score", f.confluenceScore());
        }
        n.put("volatility_regime", t.regime() == null ? null : t.regime().name());
        n.put("pillars_fired", String.join("+", t.reasons()));
        n.set("bars", barWindow(series, t));
        return n;
    }

    /** Bars from (entryIndex - PAD) through (exitIndex + PAD), clamped to the series. */
    private static ArrayNode barWindow(BarSeries series, TradeResult t) {
        int entryIdx = indexAtOrAfter(series, t.entryTime().toInstant());
        int exitIdx = indexAtOrAfter(series, t.exitTime().toInstant());
        int from = Math.max(series.getBeginIndex(), entryIdx - PAD);
        int to = Math.min(series.getEndIndex(), exitIdx + PAD);
        ArrayNode bars = MAPPER.createArrayNode();
        for (int i = from; i <= to; i++) {
            Bar bar = series.getBar(i);
            ObjectNode b = bars.addObject();
            b.put("t", bar.getEndTime().getEpochSecond());
            b.put("o", bar.getOpenPrice().doubleValue());
            b.put("h", bar.getHighPrice().doubleValue());
            b.put("l", bar.getLowPrice().doubleValue());
            b.put("c", bar.getClosePrice().doubleValue());
        }
        return bars;
    }

    private static int indexAtOrAfter(BarSeries series, Instant ts) {
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            if (!series.getBar(i).getEndTime().isBefore(ts)) {
                return i;
            }
        }
        return series.getEndIndex();
    }
}
