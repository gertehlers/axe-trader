package io.g3tech.axetrader.backtest.discovery.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.discovery.model.PathPoint;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Optional;

/**
 * Local versioned SQLite persistence for discovery data. Observable state and future path labels
 * use separate tables and separate read APIs to retain the no-leakage boundary in storage.
 */
public final class DiscoveryStore implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, Double>> FEATURE_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<Integer, Double>> HORIZON_MAP = new TypeReference<>() { };

    private final Connection connection;

    private DiscoveryStore(Connection connection) {
        this.connection = connection;
    }

    public static DiscoveryStore open(Path database) {
        try {
            if (database.getParent() != null) {
                Files.createDirectories(database.getParent());
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            applySchema(connection);
            return new DiscoveryStore(connection);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not open discovery store at " + database, exception);
        }
    }

    public long beginRun(DiscoveryRun run) {
        String sql = """
                INSERT INTO discovery_run (run_key, input_data_sha256, config_sha256, feature_schema_version,
                    score_version, source_commit, source_dirty, instrument, timeframe_min, window_from, window_to)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            statement.setString(index++, run.runKey());
            statement.setString(index++, run.inputDataSha256());
            statement.setString(index++, run.configSha256());
            statement.setString(index++, run.featureSchemaVersion());
            statement.setString(index++, run.scoreVersion());
            statement.setString(index++, run.sourceCommit());
            statement.setInt(index++, run.sourceDirty() ? 1 : 0);
            statement.setString(index++, run.instrument());
            statement.setInt(index++, run.timeframeMinutes());
            statement.setString(index++, run.windowFrom().toString());
            statement.setString(index, run.windowTo().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key for discovery run");
                }
                return keys.getLong(1);
            }
        } catch (SQLException exception) {
            throw failed("begin discovery run", exception);
        }
    }

    public void saveObservation(long runId, ObservableState state) {
        String sql = """
                INSERT INTO observation (run_id, instrument, timeframe_min, signal_ts, direction, signal_index,
                    entry_index, entry_atr, minutes_to_trading_close, features_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try {
            inTransaction(() -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    ObservationId id = state.id();
                    int index = 1;
                    statement.setLong(index++, runId);
                    statement.setString(index++, id.instrument());
                    statement.setInt(index++, id.timeframeMinutes());
                    statement.setString(index++, id.signalTime().toString());
                    statement.setString(index++, id.direction().name());
                    statement.setInt(index++, state.signalIndex());
                    statement.setInt(index++, state.entryIndex());
                    statement.setDouble(index++, state.entryAtr());
                    statement.setInt(index++, state.minutesToTradingClose());
                    statement.setString(index, json(state.features().values()));
                    statement.executeUpdate();
                }
            });
        } catch (SQLException exception) {
            if (isUniqueViolation(exception)) {
                throw new IllegalStateException("Duplicate observation identity for run " + runId, exception);
            }
            throw failed("save observation", exception);
        }
    }

    public void saveExclusion(long runId, ObservationExclusion exclusion) {
        String sql = """
                INSERT INTO observation_exclusion (run_id, instrument, timeframe_min, signal_ts, direction,
                    signal_index, reason, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            ObservationId id = exclusion.id();
            int index = 1;
            statement.setLong(index++, runId);
            statement.setString(index++, id.instrument());
            statement.setInt(index++, id.timeframeMinutes());
            statement.setString(index++, id.signalTime().toString());
            statement.setString(index++, id.direction().name());
            statement.setInt(index++, exclusion.signalIndex());
            statement.setString(index++, exclusion.reason().name());
            statement.setString(index, exclusion.detail());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failed("save observation exclusion", exception);
        }
    }

    public void saveLabel(long runId, ObservationId id, ForwardPathLabel label) {
        String observationSql = """
                SELECT id FROM observation WHERE run_id = ? AND instrument = ? AND timeframe_min = ?
                    AND signal_ts = ? AND direction = ?
                """;
        String labelSql = """
                INSERT INTO forward_label (observation_id, status, entry_price, entry_ts, exit_path_json,
                    mfe_points, mfe_atr, mae_points, mae_atr, mae_before_mfe, excursion_order,
                    horizon_returns_points_json, horizon_returns_atr_json, directional_efficiency,
                    time_to_mfe_bars, time_to_mae_bars)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try {
            inTransaction(() -> {
                long observationId;
                try (PreparedStatement observation = connection.prepareStatement(observationSql)) {
                    setObservationIdentity(observation, runId, id);
                    try (ResultSet rows = observation.executeQuery()) {
                        if (!rows.next()) {
                            throw new IllegalStateException("Cannot label missing observation " + id);
                        }
                        observationId = rows.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(labelSql)) {
                    int index = 1;
                    statement.setLong(index++, observationId);
                    statement.setString(index++, label.status().name());
                    setNullableDouble(statement, index++, label.entryPrice());
                    statement.setString(index++, label.entryTime() == null ? null : label.entryTime().toString());
                    statement.setString(index++, pathJson(label.exitPath()));
                    statement.setDouble(index++, label.mfePoints());
                    statement.setDouble(index++, label.mfeAtr());
                    statement.setDouble(index++, label.maePoints());
                    statement.setDouble(index++, label.maeAtr());
                    statement.setInt(index++, label.maeBeforeMfe() ? 1 : 0);
                    statement.setString(index++, label.excursionOrder().name());
                    statement.setString(index++, json(label.horizonReturnsPoints()));
                    statement.setString(index++, json(label.horizonReturnsAtr()));
                    statement.setDouble(index++, label.directionalEfficiency());
                    statement.setInt(index++, label.timeToMfeBars());
                    statement.setInt(index, label.timeToMaeBars());
                    statement.executeUpdate();
                }
            });
        } catch (SQLException exception) {
            throw failed("save forward path label", exception);
        }
    }

    public void appendWindowSpent(long runId, Instant from, Instant to, String candidateId) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("spent window must be half-open with from before to");
        }
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        String sql = """
                INSERT INTO window_spent (run_id, window_from, window_to, candidate_id, spent_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            statement.setString(2, from.toString());
            statement.setString(3, to.toString());
            statement.setString(4, candidateId);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isUniqueViolation(exception)) {
                throw new IllegalStateException("Discovery window is already spent: " + from + " to " + to, exception);
            }
            throw failed("append spent window", exception);
        }
    }

    /** Reads backward-only state without exposing a forward label. */
    public Optional<ObservableState> findObservableState(long runId, ObservationId id) {
        String sql = """
                SELECT signal_index, entry_index, entry_atr, minutes_to_trading_close, features_json
                FROM observation WHERE run_id = ? AND instrument = ? AND timeframe_min = ?
                    AND signal_ts = ? AND direction = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setObservationIdentity(statement, runId, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ObservableState(id, rows.getInt("signal_index"), rows.getInt("entry_index"),
                        rows.getDouble("entry_atr"), rows.getInt("minutes_to_trading_close"),
                        new FeatureVector(JSON.readValue(rows.getString("features_json"), FEATURE_MAP))));
            }
        } catch (SQLException | IOException exception) {
            throw failed("read observable state", exception);
        }
    }

    /** Reads future-only labels separately from observable-state reads. */
    public Optional<ForwardPathLabel> findForwardPathLabel(long runId, ObservationId id) {
        String sql = """
                SELECT l.* FROM forward_label l JOIN observation o ON o.id = l.observation_id
                WHERE o.run_id = ? AND o.instrument = ? AND o.timeframe_min = ?
                    AND o.signal_ts = ? AND o.direction = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setObservationIdentity(statement, runId, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(readLabel(rows));
            }
        } catch (SQLException | IOException exception) {
            throw failed("read forward path label", exception);
        }
    }

    public List<ObservableState> readObservableStates(long runId) {
        String sql = """
                SELECT instrument, timeframe_min, signal_ts, direction, signal_index, entry_index, entry_atr,
                    minutes_to_trading_close, features_json FROM observation WHERE run_id = ? ORDER BY id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ObservableState> states = new ArrayList<>();
                while (rows.next()) {
                    ObservationId id = readObservationId(rows);
                    states.add(new ObservableState(id, rows.getInt("signal_index"), rows.getInt("entry_index"),
                            rows.getDouble("entry_atr"), rows.getInt("minutes_to_trading_close"),
                            new FeatureVector(JSON.readValue(rows.getString("features_json"), FEATURE_MAP))));
                }
                return List.copyOf(states);
            }
        } catch (SQLException | IOException exception) {
            throw failed("read observable states", exception);
        }
    }

    public List<ForwardPathLabel> readForwardPathLabels(long runId) {
        String sql = """
                SELECT l.* FROM forward_label l JOIN observation o ON o.id = l.observation_id
                WHERE o.run_id = ? ORDER BY o.id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ForwardPathLabel> labels = new ArrayList<>();
                while (rows.next()) {
                    labels.add(readLabel(rows));
                }
                return List.copyOf(labels);
            }
        } catch (SQLException | IOException exception) {
            throw failed("read forward path labels", exception);
        }
    }

    private static void applySchema(Connection connection) throws IOException, SQLException {
        try (InputStream stream = DiscoveryStore.class.getResourceAsStream("/discovery/V1__discovery_schema.sql")) {
            if (stream == null) {
                throw new IllegalStateException("Missing discovery schema resource");
            }
            String schema = new String(stream.readAllBytes());
            try (Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private ForwardPathLabel readLabel(ResultSet rows) throws SQLException, IOException {
        return new ForwardPathLabel(
                LabelStatus.valueOf(rows.getString("status")),
                nullableDouble(rows, "entry_price"),
                instant(rows.getString("entry_ts")),
                readPath(rows.getString("exit_path_json")),
                rows.getDouble("mfe_points"), rows.getDouble("mfe_atr"), rows.getDouble("mae_points"),
                rows.getDouble("mae_atr"), rows.getInt("mae_before_mfe") != 0,
                ForwardPathLabel.ExcursionOrder.valueOf(rows.getString("excursion_order")),
                JSON.readValue(rows.getString("horizon_returns_points_json"), HORIZON_MAP),
                JSON.readValue(rows.getString("horizon_returns_atr_json"), HORIZON_MAP),
                rows.getDouble("directional_efficiency"), rows.getInt("time_to_mfe_bars"),
                rows.getInt("time_to_mae_bars"));
    }

    private static ObservationId readObservationId(ResultSet rows) throws SQLException {
        return new ObservationId(rows.getString("instrument"), rows.getInt("timeframe_min"),
                Instant.parse(rows.getString("signal_ts")), Direction.valueOf(rows.getString("direction")));
    }

    private static void setObservationIdentity(PreparedStatement statement, long runId, ObservationId id) throws SQLException {
        statement.setLong(1, runId);
        statement.setString(2, id.instrument());
        statement.setInt(3, id.timeframeMinutes());
        statement.setString(4, id.signalTime().toString());
        statement.setString(5, id.direction().name());
    }

    private static void setNullableDouble(PreparedStatement statement, int index, double value) throws SQLException {
        if (Double.isNaN(value)) {
            statement.setNull(index, java.sql.Types.REAL);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static double nullableDouble(ResultSet rows, String column) throws SQLException {
        double value = rows.getDouble(column);
        return rows.wasNull() ? Double.NaN : value;
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String json(Object value) throws SQLException {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new SQLException("Could not serialize discovery JSON", exception);
        }
    }

    private static String pathJson(List<PathPoint> path) throws SQLException {
        List<Map<String, Object>> encoded = new ArrayList<>(path.size());
        for (PathPoint point : path) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("index", point.index());
            values.put("time", point.time().toString());
            values.put("openPrice", point.openPrice());
            values.put("highPrice", point.highPrice());
            values.put("lowPrice", point.lowPrice());
            values.put("closePrice", point.closePrice());
            encoded.add(values);
        }
        return json(encoded);
    }

    private static List<PathPoint> readPath(String encoded) throws IOException {
        List<Map<String, Object>> values = JSON.readValue(encoded, new TypeReference<>() { });
        List<PathPoint> path = new ArrayList<>(values.size());
        for (Map<String, Object> value : values) {
            path.add(new PathPoint(
                    ((Number) value.get("index")).intValue(),
                    Instant.parse((String) value.get("time")),
                    ((Number) value.get("openPrice")).doubleValue(),
                    ((Number) value.get("highPrice")).doubleValue(),
                    ((Number) value.get("lowPrice")).doubleValue(),
                    ((Number) value.get("closePrice")).doubleValue()));
        }
        return List.copyOf(path);
    }

    private void inTransaction(SqlWork work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static boolean isUniqueViolation(SQLException exception) {
        return exception.getMessage() != null && exception.getMessage().contains("UNIQUE constraint failed");
    }

    private static IllegalStateException failed(String action, Exception exception) {
        return new IllegalStateException("Could not " + action, exception);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw failed("close discovery store", exception);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
