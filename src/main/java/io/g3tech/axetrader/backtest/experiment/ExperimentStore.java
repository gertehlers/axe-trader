package io.g3tech.axetrader.backtest.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.runner.TradeFeatures;
import io.g3tech.axetrader.backtest.runner.TradeResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes backtest experiments and their trades (with entry {@link TradeFeatures}) into a committed
 * SQLite file so results can be queried across runs instead of scrolled in {@code TODO.md} — see
 * {@code docs/observability-and-exits-design.md}.
 *
 * <p>Two grains: one {@code experiment} row per run (config + headline metrics, including the
 * {@code worst_quarter_net} scalar that makes "positive every quarter" a WHERE clause), and one
 * {@code trade} row per trade (outcome + entry features, for clustering losers by cause).
 *
 * <p>Deliberately raw JDBC on its own file — no Spring datasource entanglement with the (separate)
 * history DB. The file is disposable/regenerable from the harness; it is gitignored.
 */
public final class ExperimentStore implements AutoCloseable {

    public static final Path DEFAULT_DB = Path.of("experiments", "experiments.sqlite");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection connection;

    public ExperimentStore(Path dbFile) {
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            createSchema();
        } catch (Exception e) {
            throw new IllegalStateException("Could not open experiment store at " + dbFile, e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS experiment (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_ts TEXT NOT NULL,
                    label TEXT,
                    git_commit TEXT,
                    git_dirty INTEGER,
                    config_hash TEXT NOT NULL,
                    config_json TEXT NOT NULL,
                    instrument TEXT NOT NULL,
                    timeframe_min INTEGER NOT NULL,
                    window_from TEXT NOT NULL,
                    window_to TEXT NOT NULL,
                    trades INTEGER NOT NULL,
                    trades_per_day REAL,
                    win_rate REAL,
                    net_avg_pnl REAL,
                    avg_r REAL,
                    max_drawdown REAL,
                    worst_quarter_net REAL
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trade (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    experiment_id INTEGER NOT NULL REFERENCES experiment(id),
                    entry_ts TEXT, exit_ts TEXT,
                    direction TEXT,
                    entry_price REAL, exit_price REAL,
                    pnl REAL, net_pnl REAL, r_multiple REAL,
                    is_win INTEGER,
                    exit_reason TEXT,
                    rsi_value REAL,
                    dist_to_bb_lower_atr REAL, dist_to_bb_upper_atr REAL,
                    dist_to_support_atr REAL, dist_to_resistance_atr REAL,
                    dist_to_trend_ema_atr REAL,
                    trend_slope_atr REAL,
                    atr_value REAL, atr_percentile REAL,
                    volume_ratio REAL,
                    hour_utc INTEGER, day_of_week INTEGER,
                    confluence_score INTEGER,
                    pillars_fired TEXT
                )
                """);
        }
    }

    /**
     * Persists one experiment and its trades, computing all experiment-level metrics from the trade
     * list. {@code avgSpread} is subtracted once per trade for the "net" figures. Returns the new
     * experiment id.
     */
    public long save(
            String label,
            BacktestProperties.Strategy config,
            String instrument,
            int timeframeMinutes,
            Instant windowFrom,
            Instant windowTo,
            List<TradeResult> trades,
            double avgSpread,
            long tradingDays) {
        try {
            String configJson = MAPPER.writeValueAsString(config);
            GitInfo git = GitInfo.capture();
            long experimentId = insertExperiment(
                    label, config, configJson, git, instrument, timeframeMinutes,
                    windowFrom, windowTo, trades, avgSpread, tradingDays);
            insertTrades(experimentId, trades, avgSpread);
            return experimentId;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist experiment " + label, e);
        }
    }

    private long insertExperiment(
            String label, BacktestProperties.Strategy config, String configJson, GitInfo git,
            String instrument, int timeframeMinutes, Instant windowFrom, Instant windowTo,
            List<TradeResult> trades, double avgSpread, long tradingDays) throws SQLException {

        int count = trades.size();
        long wins = trades.stream().filter(t -> t.pnl() > 0).count();
        double winRate = count == 0 ? 0 : (double) wins / count;
        double netAvgPnl = trades.stream().mapToDouble(t -> t.pnl() - avgSpread).average().orElse(0);
        double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0);
        double tradesPerDay = tradingDays == 0 ? 0 : (double) count / tradingDays;

        String sql = """
            INSERT INTO experiment (run_ts, label, git_commit, git_dirty, config_hash, config_json,
                instrument, timeframe_min, window_from, window_to, trades, trades_per_day,
                win_rate, net_avg_pnl, avg_r, max_drawdown, worst_quarter_net)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setString(i++, Instant.now().toString());
            ps.setString(i++, label);
            ps.setString(i++, git.commit());
            ps.setInt(i++, git.dirty() ? 1 : 0);
            ps.setString(i++, configHash(configJson, instrument, timeframeMinutes, windowFrom, windowTo));
            ps.setString(i++, configJson);
            ps.setString(i++, instrument);
            ps.setInt(i++, timeframeMinutes);
            ps.setString(i++, windowFrom.toString());
            ps.setString(i++, windowTo.toString());
            ps.setInt(i++, count);
            ps.setDouble(i++, tradesPerDay);
            ps.setDouble(i++, winRate);
            ps.setDouble(i++, netAvgPnl);
            ps.setDouble(i++, avgR);
            ps.setDouble(i++, maxDrawdown(trades, avgSpread));
            ps.setDouble(i, worstQuarterNet(trades, avgSpread));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertTrades(long experimentId, List<TradeResult> trades, double avgSpread) throws SQLException {
        String sql = """
            INSERT INTO trade (experiment_id, entry_ts, exit_ts, direction, entry_price, exit_price,
                pnl, net_pnl, r_multiple, is_win, exit_reason, rsi_value, dist_to_bb_lower_atr,
                dist_to_bb_upper_atr, dist_to_support_atr, dist_to_resistance_atr, dist_to_trend_ema_atr,
                trend_slope_atr, atr_value, atr_percentile, volume_ratio, hour_utc, day_of_week,
                confluence_score, pillars_fired)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        boolean prevAutoCommit = true;
        try {
            prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (TradeResult t : trades) {
                    TradeFeatures f = t.features();
                    int i = 1;
                    ps.setLong(i++, experimentId);
                    ps.setString(i++, t.entryTime().toInstant().toString());
                    ps.setString(i++, t.exitTime().toInstant().toString());
                    ps.setString(i++, t.direction().name());
                    ps.setDouble(i++, t.entryPrice());
                    ps.setDouble(i++, t.exitPrice());
                    ps.setDouble(i++, t.pnl());
                    ps.setDouble(i++, t.pnl() - avgSpread);
                    ps.setDouble(i++, t.rMultiple());
                    ps.setInt(i++, t.isWin() ? 1 : 0);
                    ps.setString(i++, t.exitReason() == null ? null : t.exitReason().name());
                    i = setFeatures(ps, i, f);
                    ps.setString(i, f == null ? null : String.join("+", t.reasons()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(prevAutoCommit);
        }
    }

    private static int setFeatures(PreparedStatement ps, int i, TradeFeatures f) throws SQLException {
        if (f == null) {
            for (int c = 0; c < 13; c++) {
                ps.setNull(i++, java.sql.Types.REAL);
            }
            return i;
        }
        ps.setDouble(i++, f.rsi());
        ps.setDouble(i++, f.distToBbLowerAtr());
        ps.setDouble(i++, f.distToBbUpperAtr());
        ps.setDouble(i++, f.distToSupportAtr());
        ps.setDouble(i++, f.distToResistanceAtr());
        if (f.distToTrendEmaAtr() == null) {
            ps.setNull(i++, java.sql.Types.REAL);
        } else {
            ps.setDouble(i++, f.distToTrendEmaAtr());
        }
        ps.setDouble(i++, f.trendSlopeAtr());
        ps.setDouble(i++, f.atr());
        ps.setDouble(i++, f.atrPercentile());
        ps.setDouble(i++, f.volumeRatio());
        ps.setInt(i++, f.hourUtc());
        ps.setInt(i++, f.dayOfWeek());
        ps.setInt(i++, f.confluenceScore());
        return i;
    }

    /** Min over calendar quarters of that quarter's mean net pnl — the "positive every quarter" gate. */
    private static double worstQuarterNet(List<TradeResult> trades, double avgSpread) {
        Map<String, List<Double>> byQuarter = new LinkedHashMap<>();
        for (TradeResult t : trades) {
            String q = t.entryTime().getYear() + "Q" + ((t.entryTime().getMonthValue() - 1) / 3 + 1);
            byQuarter.computeIfAbsent(q, k -> new ArrayList<>()).add(t.pnl() - avgSpread);
        }
        double worst = Double.POSITIVE_INFINITY;
        for (List<Double> q : byQuarter.values()) {
            worst = Math.min(worst, q.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return byQuarter.isEmpty() ? 0 : worst;
    }

    /** Max peak-to-trough drop of the cumulative net-pnl equity curve (trades in entry order). */
    private static double maxDrawdown(List<TradeResult> trades, double avgSpread) {
        double equity = 0;
        double peak = 0;
        double maxDd = 0;
        for (TradeResult t : trades) {
            equity += t.pnl() - avgSpread;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }
        return maxDd;
    }

    private static String configHash(
            String configJson, String instrument, int timeframe, Instant from, Instant to) {
        try {
            String canonical = configJson + "|" + instrument + "|" + timeframe + "|" + from + "|" + to;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int b = 0; b < 6; b++) {
                sb.append(String.format("%02x", digest[b]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "nohash";
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort
        }
    }

    /** Short git commit + dirty flag, so a result is traceable to exact code (dirty = not reproducible). */
    private record GitInfo(String commit, boolean dirty) {
        static GitInfo capture() {
            String commit = run("git", "rev-parse", "--short", "HEAD");
            String status = run("git", "status", "--porcelain");
            return new GitInfo(commit == null ? "unknown" : commit.trim(), status == null || !status.isBlank());
        }

        private static String run(String... cmd) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                return p.waitFor() == 0 ? out : null;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
