package io.g3tech.axetrader.backtest.discovery.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.OpportunityZone;
import io.g3tech.axetrader.backtest.discovery.model.FeatureVector;
import io.g3tech.axetrader.backtest.discovery.model.ForwardPathLabel;
import io.g3tech.axetrader.backtest.discovery.model.LabelStatus;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.model.ObservationExclusion;
import io.g3tech.axetrader.backtest.discovery.model.ObservationId;
import io.g3tech.axetrader.backtest.discovery.model.PathPoint;
import io.g3tech.axetrader.backtest.discovery.validation.FrozenCandidate;
import io.g3tech.axetrader.backtest.discovery.validation.MonthlyResult;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationStatistics;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationSummary;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationTrade;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Local versioned SQLite persistence for discovery data. Observable state and future path labels
 * use separate tables and separate read APIs to retain the no-leakage boundary in storage.
 */
public final class DiscoveryStore implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(timeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, Double>> FEATURE_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<Integer, Double>> HORIZON_MAP = new TypeReference<>() { };

    private final Connection connection;

    private static SimpleModule timeModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return Instant.parse(parser.getValueAsString());
            }
        });
        module.addSerializer(YearMonth.class, new JsonSerializer<>() {
            @Override
            public void serialize(YearMonth value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        module.addDeserializer(YearMonth.class, new JsonDeserializer<>() {
            @Override
            public YearMonth deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return YearMonth.parse(parser.getValueAsString());
            }
        });
        return module;
    }

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

    public boolean windowSpent(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM window_spent WHERE window_from = ? AND window_to = ?")) {
            statement.setString(1, from.toString());
            statement.setString(2, to.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw failed("read spent window", exception);
        }
    }

    public void saveZone(long runId, OpportunityZone zone) {
        Objects.requireNonNull(zone, "zone");
        try {
            inTransaction(() -> {
                long zoneId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO opportunity_zone (run_id, direction, score_version, opened_at, closed_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, runId);
                    statement.setString(2, zone.direction().name());
                    statement.setString(3, zone.scoreVersion());
                    statement.setString(4, zone.firstSignalTime().toString());
                    statement.setString(5, zone.lastSignalTime().toString());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("No generated key for opportunity zone");
                        }
                        zoneId = keys.getLong(1);
                    }
                }
                try (PreparedStatement observation = connection.prepareStatement("""
                        SELECT id FROM observation WHERE run_id = ? AND instrument = ? AND timeframe_min = ?
                            AND signal_ts = ? AND direction = ?
                        """);
                     PreparedStatement member = connection.prepareStatement(
                             "INSERT INTO zone_member (zone_id, observation_id) VALUES (?, ?)")) {
                    for (var score : zone.scores()) {
                        setObservationIdentity(observation, runId, score.labelledObservation().state().id());
                        try (ResultSet rows = observation.executeQuery()) {
                            if (!rows.next()) {
                                throw new IllegalStateException("Cannot persist zone member before observation");
                            }
                            member.setLong(1, zoneId);
                            member.setLong(2, rows.getLong(1));
                            member.addBatch();
                        }
                    }
                    member.executeBatch();
                }
            });
        } catch (SQLException exception) {
            throw failed("save opportunity zone", exception);
        }
    }

    /**
     * Persists a frozen rule and the complete derivation interval before any later monthly fold can
     * read it. The candidate definition is its canonical JSON, so threshold-order variants share a
     * reproducible content identity.
     */
    public long registerCandidate(long runId, CandidateRule candidate, Instant derivationFrom, Instant derivationTo) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(derivationFrom, "derivationFrom");
        Objects.requireNonNull(derivationTo, "derivationTo");
        if (!derivationFrom.isBefore(derivationTo)) {
            throw new IllegalArgumentException("candidate derivation window must be half-open with from before to");
        }
        long[] candidateId = new long[1];
        try {
            inTransaction(() -> {
                long familyId = insertPatternFamily(runId, candidate);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO candidate_rule (pattern_family_id, definition_json, derivation_from, derivation_to,
                            registered_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, familyId);
                    statement.setString(2, candidate.canonicalJson());
                    statement.setString(3, derivationFrom.toString());
                    statement.setString(4, derivationTo.toString());
                    statement.setString(5, Instant.now().toString());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("No generated key for candidate rule");
                        }
                        candidateId[0] = keys.getLong(1);
                    }
                }
            });
            return candidateId[0];
        } catch (SQLException exception) {
            throw failed("register candidate rule", exception);
        }
    }

    public void saveFrozenCandidate(long candidateRuleId, FrozenCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO exit_policy (candidate_rule_id, definition_json, created_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, candidateRuleId);
            statement.setString(2, json(candidate));
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failed("save frozen candidate", exception);
        }
    }

    public void saveValidation(long candidateRuleId, List<ValidationTrade> trades) {
        List<ValidationTrade> immutableTrades = List.copyOf(Objects.requireNonNull(trades, "trades"));
        try {
            inTransaction(() -> {
                try (PreparedStatement trade = connection.prepareStatement("""
                        INSERT INTO validation_trade (candidate_rule_id, entry_ts, exit_ts, result_json)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    for (ValidationTrade result : immutableTrades) {
                        trade.setLong(1, candidateRuleId);
                        trade.setString(2, result.entryTime().toString());
                        trade.setString(3, result.exitTime().toString());
                        trade.setString(4, json(result));
                        trade.addBatch();
                    }
                    trade.executeBatch();
                }
                for (var monthly : ValidationStatistics.monthlyResults(immutableTrades)) {
                    upsertMonthlyResult(candidateRuleId, monthly);
                }
            });
        } catch (SQLException exception) {
            throw failed("save validation results", exception);
        }
    }

    public void saveMonthlyResult(long candidateRuleId, MonthlyResult result) {
        Objects.requireNonNull(result, "result");
        try {
            upsertMonthlyResult(candidateRuleId, result);
        } catch (SQLException exception) {
            throw failed("save monthly validation result", exception);
        }
    }

    private void upsertMonthlyResult(long candidateRuleId, MonthlyResult monthly) throws SQLException {
        try (PreparedStatement result = connection.prepareStatement("""
                INSERT INTO monthly_result (candidate_rule_id, month, result_json)
                VALUES (?, ?, ?)
                ON CONFLICT(candidate_rule_id, month) DO UPDATE SET result_json = excluded.result_json
                """)) {
            result.setLong(1, candidateRuleId);
            result.setString(2, monthly.month().toString());
            result.setString(3, json(monthly));
            result.executeUpdate();
        }
    }

    public Optional<StoredCandidate> findFrozenCandidate(String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ep.candidate_rule_id, ep.definition_json, pf.run_id
                FROM exit_policy ep
                JOIN candidate_rule cr ON cr.id = ep.candidate_rule_id
                JOIN pattern_family pf ON pf.id = cr.pattern_family_id
                ORDER BY ep.id
                """);
             ResultSet rows = statement.executeQuery()) {
            FrozenCandidate matchedCandidate = null;
            long matchedDatabaseCandidateId = 0;
            long matchedRunId = 0;
            while (rows.next()) {
                FrozenCandidate candidate = JSON.readValue(
                        rows.getString("definition_json"), FrozenCandidate.class);
                if (candidate.id().equals(candidateId)) {
                    if (matchedCandidate != null) {
                        throw new IllegalStateException(
                                "Ambiguous frozen candidate id across discovery runs: " + candidateId);
                    }
                    matchedCandidate = candidate;
                    matchedDatabaseCandidateId = rows.getLong("candidate_rule_id");
                    matchedRunId = rows.getLong("run_id");
                }
            }
            if (matchedCandidate == null) {
                return Optional.empty();
            }
            List<ValidationTrade> trades = readValidationTrades(matchedDatabaseCandidateId);
            ValidationSummary summary = summary(
                    matchedCandidate, trades, readMonthlyResults(matchedDatabaseCandidateId));
            return Optional.of(new StoredCandidate(
                    matchedRunId, matchedDatabaseCandidateId, matchedCandidate, summary));
        } catch (SQLException | IOException exception) {
            throw failed("read frozen candidate", exception);
        }
    }

    public void appendExperimentEvent(long runId, String eventType, Object event) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO experiment_event (run_id, event_type, event_json, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, runId);
            statement.setString(2, eventType);
            statement.setString(3, json(event));
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failed("append experiment event", exception);
        }
    }

    private List<ValidationTrade> readValidationTrades(long candidateRuleId) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT result_json FROM validation_trade
                WHERE candidate_rule_id = ? ORDER BY entry_ts, id
                """)) {
            statement.setLong(1, candidateRuleId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ValidationTrade> trades = new ArrayList<>();
                while (rows.next()) {
                    trades.add(JSON.readValue(rows.getString("result_json"), ValidationTrade.class));
                }
                return List.copyOf(trades);
            }
        }
    }

    private List<MonthlyResult> readMonthlyResults(long candidateRuleId) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT result_json FROM monthly_result
                WHERE candidate_rule_id = ? ORDER BY month
                """)) {
            statement.setLong(1, candidateRuleId);
            try (ResultSet rows = statement.executeQuery()) {
                List<MonthlyResult> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(JSON.readValue(rows.getString("result_json"), MonthlyResult.class));
                }
                return List.copyOf(results);
            }
        }
    }

    private static ValidationSummary summary(
            FrozenCandidate candidate, List<ValidationTrade> trades, List<MonthlyResult> persistedMonths) {
        ValidationSummary calculated = trades.isEmpty()
                ? new ValidationSummary(
                        candidate.id(), candidate.rule().independentZones(), List.of(), 0, 0, 0,
                        0, 0, Map.of(candidate.rule().direction(), 0.0),
                        java.util.Set.of(candidate.rule().direction()), Double.NaN)
                : ValidationStatistics.summarize(candidate, trades);
        if (persistedMonths.isEmpty()) {
            return calculated;
        }
        List<Double> nets = persistedMonths.stream().map(MonthlyResult::netPnl).sorted().toList();
        int middle = nets.size() / 2;
        double median = nets.size() % 2 == 0
                ? (nets.get(middle - 1) + nets.get(middle)) / 2.0
                : nets.get(middle);
        List<MonthlyResult> sampled = persistedMonths.stream().filter(MonthlyResult::sampled).toList();
        double profitablePercentage = sampled.isEmpty() ? Double.NaN
                : sampled.stream().filter(month -> month.netPnl() > 0).count() * 100.0 / sampled.size();
        return new ValidationSummary(
                calculated.candidateId(), calculated.independentZones(), persistedMonths,
                calculated.totalNet(), median, nets.getFirst(), calculated.maximumDrawdown(),
                calculated.netToMaximumDrawdown(), calculated.totalNetByDirection(),
                calculated.enabledDirections(), profitablePercentage);
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
            ensureCandidateDerivationColumns(connection);
        }
    }

    private static void ensureCandidateDerivationColumns(Connection connection) throws SQLException {
        java.util.Set<String> columns = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(candidate_rule)")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        try (Statement statement = connection.createStatement()) {
            if (!columns.contains("derivation_from")) {
                statement.execute("ALTER TABLE candidate_rule ADD COLUMN derivation_from TEXT");
            }
            if (!columns.contains("derivation_to")) {
                statement.execute("ALTER TABLE candidate_rule ADD COLUMN derivation_to TEXT");
            }
        }
    }

    private long insertPatternFamily(long runId, CandidateRule candidate) throws SQLException {
        String familyDefinition = candidate.direction().name() + "|" + candidate.clauses().stream()
                .map(clause -> clause.feature() + "|" + clause.operator().name())
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pattern_family (run_id, direction, definition_json, created_at)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, runId);
            statement.setString(2, candidate.direction().name());
            statement.setString(3, familyDefinition);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key for pattern family");
                }
                return keys.getLong(1);
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

    public record StoredCandidate(
            long runId,
            long databaseCandidateId,
            FrozenCandidate candidate,
            ValidationSummary developmentSummary) {
        public StoredCandidate {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(developmentSummary, "developmentSummary");
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
