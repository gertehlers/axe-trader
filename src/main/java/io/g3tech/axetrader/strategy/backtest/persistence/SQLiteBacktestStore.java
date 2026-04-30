package io.g3tech.axetrader.strategy.backtest.persistence;

import io.g3tech.axetrader.strategy.EntrySignal;
import io.g3tech.axetrader.strategy.backtest.BacktestEvent;
import io.g3tech.axetrader.strategy.backtest.BacktestResult;
import io.g3tech.axetrader.strategy.backtest.BacktestTrade;
import io.g3tech.axetrader.strategy.backtest.data.HistoricalPriceRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.StringJoiner;

@Component
public class SQLiteBacktestStore {

    private final BacktestPersistenceSettings settings;

    @Autowired
    public SQLiteBacktestStore(@Value("${axe-trader.database.path:data/axe-trader.sqlite}") String databasePath) {
        this(new BacktestPersistenceSettings(databasePath));
    }

    public SQLiteBacktestStore(BacktestPersistenceSettings settings) {
        this.settings = settings;
    }

    public long save(HistoricalPriceRequest request, BacktestResult result) {
        ensureParentDirectory();

        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            createSchema(connection);

            var runId = insertRun(connection, request, result);
            for (var event : result.events()) {
                insertSignal(connection, runId, event);
            }
            for (var trade : result.trades()) {
                insertTrade(connection, runId, trade);
            }

            connection.commit();
            return runId;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save backtest result to SQLite", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + settings.databasePath());
    }

    private void ensureParentDirectory() {
        var parent = Path.of(settings.databasePath()).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }

        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create SQLite database directory: " + parent, e);
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists backtest_run (
                        id integer primary key autoincrement,
                        epic text not null,
                        resolution text not null,
                        requested_from text,
                        requested_to text,
                        requested_max integer not null,
                        candles_processed integer not null,
                        warmup_candles integer not null,
                        signals_detected integer not null,
                        trades_closed integer not null,
                        win_rate real not null,
                        total_r real not null,
                        created_at text not null
                    )
                    """);
            statement.execute("""
                    create table if not exists backtest_signal (
                        id integer primary key autoincrement,
                        run_id integer not null references backtest_run(id),
                        candle_index integer not null,
                        candle_time text not null,
                        direction text not null,
                        score integer not null,
                        bias text not null,
                        entry_price text not null,
                        stop_loss text not null,
                        reasons text not null,
                        ema20 real not null,
                        ema50 real not null,
                        ema20_slope real not null,
                        rsi14 real not null,
                        atr14 real not null,
                        adx14 real not null,
                        plus_di14 real not null,
                        minus_di14 real not null
                    )
                    """);
            statement.execute("""
                    create table if not exists backtest_trade (
                        id integer primary key autoincrement,
                        run_id integer not null references backtest_run(id),
                        direction text not null,
                        entry_index integer not null,
                        entry_time text not null,
                        entry_price text not null,
                        stop_loss text not null,
                        target_price text not null,
                        exit_index integer not null,
                        exit_time text not null,
                        exit_price text not null,
                        exit_reason text not null,
                        risk_multiple real not null
                    )
                    """);
        }
    }

    private long insertRun(Connection connection, HistoricalPriceRequest request, BacktestResult result) throws SQLException {
        var sql = """
                insert into backtest_run (
                    epic, resolution, requested_from, requested_to, requested_max, candles_processed,
                    warmup_candles, signals_detected, trades_closed, win_rate, total_r, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, request.epic());
            statement.setString(2, request.resolution());
            setInstant(statement, 3, request.from());
            setInstant(statement, 4, request.to());
            statement.setInt(5, request.max());
            statement.setInt(6, result.candlesProcessed());
            statement.setInt(7, result.warmupCandles());
            statement.setInt(8, result.signalsDetected());
            statement.setInt(9, result.tradesClosed());
            statement.setDouble(10, result.winRate());
            statement.setDouble(11, result.totalRiskMultiple());
            statement.setString(12, Instant.now().toString());
            statement.executeUpdate();

            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("SQLite did not return generated backtest run id");
                }

                return keys.getLong(1);
            }
        }
    }

    private void insertSignal(Connection connection, long runId, BacktestEvent event) throws SQLException {
        var signal = event.signal();
        var indicators = signal.indicators();
        var sql = """
                insert into backtest_signal (
                    run_id, candle_index, candle_time, direction, score, bias, entry_price, stop_loss, reasons,
                    ema20, ema50, ema20_slope, rsi14, atr14, adx14, plus_di14, minus_di14
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setInt(2, event.candleIndex());
            statement.setString(3, signal.candleTime().toString());
            statement.setString(4, signal.direction().name());
            statement.setInt(5, signal.score());
            statement.setString(6, signal.bias().name());
            statement.setString(7, decimal(signal.entryPrice()));
            statement.setString(8, decimal(signal.stopLoss()));
            statement.setString(9, reasons(signal));
            statement.setDouble(10, indicators.ema20());
            statement.setDouble(11, indicators.ema50());
            statement.setDouble(12, indicators.ema20Slope());
            statement.setDouble(13, indicators.rsi14());
            statement.setDouble(14, indicators.atr14());
            statement.setDouble(15, indicators.adx14());
            statement.setDouble(16, indicators.plusDi14());
            statement.setDouble(17, indicators.minusDi14());
            statement.executeUpdate();
        }
    }

    private void insertTrade(Connection connection, long runId, BacktestTrade trade) throws SQLException {
        var sql = """
                insert into backtest_trade (
                    run_id, direction, entry_index, entry_time, entry_price, stop_loss, target_price,
                    exit_index, exit_time, exit_price, exit_reason, risk_multiple
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setString(2, trade.direction().name());
            statement.setInt(3, trade.entryIndex());
            statement.setString(4, trade.entryTime().toString());
            statement.setString(5, decimal(trade.entryPrice()));
            statement.setString(6, decimal(trade.stopLoss()));
            statement.setString(7, decimal(trade.targetPrice()));
            statement.setInt(8, trade.exitIndex());
            statement.setString(9, trade.exitTime().toString());
            statement.setString(10, decimal(trade.exitPrice()));
            statement.setString(11, trade.exitReason().name());
            statement.setDouble(12, trade.riskMultiple());
            statement.executeUpdate();
        }
    }

    private void setInstant(java.sql.PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }

        statement.setString(index, value.toString());
    }

    private String decimal(BigDecimal value) {
        return value.toPlainString();
    }

    private String reasons(EntrySignal signal) {
        var joiner = new StringJoiner("|");
        signal.reasons().forEach(joiner::add);
        return joiner.toString();
    }
}
