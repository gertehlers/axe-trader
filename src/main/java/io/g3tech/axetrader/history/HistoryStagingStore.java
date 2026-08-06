package io.g3tech.axetrader.history;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HistoryStagingStore implements AutoCloseable {

    static final int ALGORITHM_VERSION = 3;

    private static final String INSERT_PAGE = """
            INSERT INTO history_import_page (
                import_run_id, requested_from_utc, requested_to_utc, payload_hash,
                received_count, accepted_count, rejected_count)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_PRICE = """
            INSERT OR IGNORE INTO historical_price (
                id, epic, resolution, snapshot_time_utc,
                open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask,
                last_traded_volume, source, ingestion_time_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_EXCLUSION = """
            INSERT OR IGNORE INTO price_exclusion (
                import_run_id, source, epic, resolution, snapshot_time_utc, reason, detected_at_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final Connection connection;
    private final PriceValidator validator;
    private final String stageRunId;
    private final StageLease lease;

    private HistoryStagingStore(Connection connection) {
        this(connection, null, null);
    }

    private HistoryStagingStore(Connection connection, String stageRunId, StageLease lease) {
        this.connection = connection;
        this.validator = new PriceValidator();
        this.stageRunId = stageRunId;
        this.lease = lease;
    }

    public static HistoryStagingStore open(Path database) {
        try {
            Connection connection = connect(database);
            applySchema(connection);
            return new HistoryStagingStore(connection);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not open history staging store at " + database, exception);
        }
    }

    static HistoryStagingStore openForStage(Path database, HistoryImportRequest request, Duration baseWindow) {
        boolean existing = Files.exists(database);
        Connection connection = null;
        StageLease lease = null;
        try {
            existing = prepareDatabaseFile(database, existing);
            lease = StageLease.acquire(database);
            connection = connect(lease.databasePath());
            enableForeignKeys(connection);
            String runId = importRunId(request);
            HistoryStagingStore store = new HistoryStagingStore(connection, runId, lease);
            if (existing) {
                store.requireResumableIdentity(request);
            } else {
                applySchema(connection);
                store.initializeRun(request, baseWindow);
            }
            return store;
        } catch (Exception exception) {
            closeAfterOpenFailure(connection, exception);
            closeLeaseAfterOpenFailure(lease, exception);
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("identity") || message.contains("version") || message.contains("completed")
                    || message.contains("already active") || message.contains("foreign run")
                    || message.contains("unsafe staging filesystem")
                    || message.contains("hard-linked staging database")) {
                throw exception instanceof IllegalStateException state ? state
                        : new IllegalStateException(message, exception);
            }
            throw new IllegalStateException("Existing staging database has no valid import-run metadata", exception);
        }
    }

    private static boolean prepareDatabaseFile(Path database, boolean existing) throws IOException {
        if (database.getParent() != null) {
            Files.createDirectories(database.getParent());
        }
        if (existing) {
            return true;
        }
        try {
            Files.createFile(database);
            return false;
        } catch (FileAlreadyExistsException wonByAnotherProcess) {
            return true;
        }
    }

    private static void closeLeaseAfterOpenFailure(StageLease lease, Exception failure) {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeAfterOpenFailure(Connection connection, Exception failure) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Connection connect(Path database) throws Exception {
        if (database.getParent() != null) {
            Files.createDirectories(database.getParent());
        }
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static void enableForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    public void writePage(ImportedPage page, HistoryImportRequest request) {
        validatePageBounds(page, request);
        try {
            connection.setAutoCommit(false);
            writePageInTransaction(page, request);
            connection.commit();
        } catch (RuntimeException | SQLException exception) {
            rollback();
            throw exception instanceof IllegalStateException state ? state
                    : new IllegalStateException("Could not write imported history page", exception);
        } finally {
            restoreAutoCommit();
        }
    }

    WorkItem nextPending() {
        requireStageOwner();
        String sql = """
                SELECT work_id, requested_from_utc, requested_to_utc
                FROM history_import_work WHERE import_run_id = ? AND state = 'PENDING' ORDER BY work_id LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stageRunId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? new WorkItem(rows.getLong(1), stageRunId, Instant.parse(rows.getString(2)),
                        Instant.parse(rows.getString(3))) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read pending history interval", exception);
        }
    }

    void process(WorkItem work, ImportedPage page, HistoryImportRequest request) {
        if (!work.fromInclusive().equals(page.requestedFrom()) || !work.toExclusive().equals(page.requestedTo())) {
            throw new IllegalArgumentException("Page does not match pending work interval");
        }
        if (!importRunId(request).equals(work.importRunId())) {
            throw new IllegalArgumentException("Work item belongs to a different import run");
        }
        validatePageBounds(page, request);
        List<ValidatedPrice> validated = validate(page);
        Set<Instant> observed = coveredTimestamps(validated);
        try {
            connection.setAutoCommit(false);
            requirePending(work);
            long newCoverage = 0;
            for (Instant timestamp : observed) {
                if (!minuteCovered(request, timestamp)) {
                    newCoverage++;
                }
            }
            if (!page.prices().isEmpty() && newCoverage == 0) {
                throw new IllegalStateException("Nonempty provider response made no new coverage progress");
            }
            writePageInTransaction(page, request, validated);
            if (page.prices().isEmpty()) {
                insertClosure(work, request, page.payloadHash());
            } else {
                enqueueMissingRuns(work, request, observed);
            }
            markProcessed(work);
            connection.commit();
        } catch (RuntimeException | SQLException exception) {
            rollback();
            throw exception instanceof IllegalStateException state ? state
                    : new IllegalStateException("Could not process imported history interval", exception);
        } finally {
            restoreAutoCommit();
        }
    }

    void recordFailure(WorkItem work, RuntimeException failure) {
        String sql = """
                UPDATE history_import_work SET last_error = ?, last_attempt_utc = ?
                WHERE work_id = ? AND import_run_id = ? AND state = 'PENDING'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            statement.setString(2, Instant.now().toString());
            statement.setLong(3, work.id());
            statement.setString(4, work.importRunId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            failure.addSuppressed(exception);
        }
    }

    long pendingCount() {
        requireStageOwner();
        return pendingCount(stageRunId);
    }

    private long pendingCount(String runId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM history_import_work WHERE import_run_id = ? AND state = 'PENDING'")) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                return rows.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count pending history intervals", exception);
        }
    }

    public long countAccepted(HistoryImportRequest request) {
        String sql = """
                SELECT COUNT(*) FROM historical_price
                WHERE source = ? AND epic = ? AND resolution = ?
                    AND snapshot_time_utc >= ? AND snapshot_time_utc < ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setRequestIdentity(statement, request);
            return singleLong(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count accepted prices", exception);
        }
    }

    public List<PriceExclusion> exclusions(HistoryImportRequest request) {
        String sql = """
                SELECT snapshot_time_utc, reason FROM price_exclusion
                WHERE import_run_id = ? ORDER BY snapshot_time_utc, reason
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                List<PriceExclusion> exclusions = new ArrayList<>();
                while (rows.next()) {
                    exclusions.add(new PriceExclusion(rows.getString(1), rows.getString(2)));
                }
                return List.copyOf(exclusions);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read price exclusions", exception);
        }
    }

    public HistoryImportAudit audit(HistoryImportRequest request) {
        long acceptedMinutes = countAccepted(request);
        long excludedMinutes = excludedMinuteCount(request);
        Map<String, Long> exclusionsByReason = exclusionsByReason(request);
        Instant[] actualBounds = actualBounds(request);
        long malformedAcceptedCount = malformedAcceptedCount(request);
        HistoryCoverage.Assessment coverage = coverage(request);
        long pending = tableExists("history_import_work") ? pendingCount(importRunId(request)) : 0;
        long received = acceptedMinutes + excludedMinutes;
        PageCounts raw = pageCounts(request);
        long closureMinutes = coverage.recognizedSessionClosures().stream()
                .mapToLong(closure -> Duration.between(closure.fromInclusive(), closure.toExclusive()).toMinutes())
                .sum();
        long requestedMinutes = Duration.between(request.from(), request.to()).toMinutes();
        boolean exactNonOverlappingCoverage = acceptedMinutes + excludedMinutes + closureMinutes == requestedMinutes;
        return new HistoryImportAudit(
                request.from(), request.to(), actualBounds[0], actualBounds[1],
                received, acceptedMinutes, excludedMinutes,
                acceptedMinutes, excludedMinutes, 0, exclusionsByReason,
                coverage.recognizedSessionClosures(), coverage.continuityGaps(),
                raw.observationCount(), raw.receivedCount(), raw.acceptedCount(), raw.rejectedCount(), pending,
                malformedAcceptedCount == 0 && pending == 0 && exactNonOverlappingCoverage
                        && coverage.continuityGaps().isEmpty());
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            connection.close();
        } catch (SQLException exception) {
            failure = new IllegalStateException("Could not close history staging store", exception);
        }
        if (lease != null) {
            try {
                lease.close();
            } catch (RuntimeException leaseFailure) {
                if (failure == null) {
                    failure = leaseFailure;
                } else {
                    failure.addSuppressed(leaseFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public record PriceExclusion(String snapshotTimeUtc, String reason) { }

    record WorkItem(long id, String importRunId, Instant fromInclusive, Instant toExclusive) { }

    private void initializeRun(HistoryImportRequest request, Duration baseWindow) throws SQLException {
        connection.setAutoCommit(false);
        try {
            String sql = """
                    INSERT INTO history_import_run (
                        import_run_id, algorithm_version, source, epic, resolution,
                        requested_from_utc, requested_to_utc, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, importRunId(request));
                statement.setInt(2, ALGORITHM_VERSION);
                statement.setString(3, request.source());
                statement.setString(4, request.epic());
                statement.setString(5, request.resolution());
                statement.setString(6, request.from().toString());
                statement.setString(7, request.to().toString());
                statement.setString(8, Instant.now().toString());
                statement.executeUpdate();
            }
            for (Instant from = request.from(); from.isBefore(request.to()); ) {
                Instant candidate = from.plus(baseWindow);
                Instant to = candidate.isBefore(request.to()) ? candidate : request.to();
                insertWork(request, from, to);
                from = to;
            }
            connection.commit();
        } catch (SQLException exception) {
            rollback();
            throw exception;
        } finally {
            restoreAutoCommit();
        }
    }

    private void requireResumableIdentity(HistoryImportRequest request) throws SQLException {
        if (!tableExists("history_import_run")) {
            throw new IllegalStateException("Existing staging database has no valid import-run metadata");
        }
        if (tableExists("history_import_completion")) {
            throw new IllegalStateException("Refusing to resume a completed staging database");
        }
        String sql = """
                SELECT import_run_id, algorithm_version, source, epic, resolution, requested_from_utc, requested_to_utc
                FROM history_import_run
                """;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("Existing staging database has no valid import-run metadata");
            }
            String storedRunId = rows.getString(1);
            if (!stageRunId.equals(storedRunId)) {
                throw new IllegalStateException("Stored run identity does not match the configured request");
            }
            int version = rows.getInt(2);
            if (version != ALGORITHM_VERSION) {
                throw new IllegalStateException("Unknown staging algorithm version: " + version);
            }
            boolean same = request.source().equals(rows.getString(3))
                    && request.epic().equals(rows.getString(4))
                    && request.resolution().equals(rows.getString(5))
                    && request.from().equals(Instant.parse(rows.getString(6)))
                    && request.to().equals(Instant.parse(rows.getString(7)));
            if (!same || rows.next()) {
                throw new IllegalStateException("Configured request identity does not match the incomplete stage");
            }
        }
        if (!tableExists("history_import_work") || !tableExists("history_import_closure")) {
            throw new IllegalStateException("Existing staging database has no valid import-run metadata");
        }
        rejectForeignRunRows("history_import_work");
        rejectForeignRunRows("history_import_closure");
        rejectForeignRunRows("history_import_page");
        rejectForeignRunRows("price_exclusion");
    }

    private void rejectForeignRunRows(String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE import_run_id <> ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stageRunId);
            if (singleLong(statement) != 0) {
                throw new IllegalStateException("Staging database contains rows for a foreign run in " + table);
            }
        }
    }

    private void writePageInTransaction(ImportedPage page, HistoryImportRequest request) throws SQLException {
        writePageInTransaction(page, request, validate(page));
    }

    private void writePageInTransaction(ImportedPage page, HistoryImportRequest request,
                                        List<ValidatedPrice> prices) throws SQLException {
        long acceptedCount = prices.stream().filter(ValidatedPrice::accepted).count();
        long rejectedCount = prices.size() - acceptedCount;
        String runId = importRunId(request);
        ExistingPage existing = existingPage(page, runId);
        if (existing != null) {
            if (!existing.matches(page.payloadHash(), page.prices().size(), acceptedCount, rejectedCount)) {
                throw new IllegalStateException("Page bounds already have a different payload or counts");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PAGE)) {
            statement.setString(1, runId);
            statement.setString(2, page.requestedFrom().toString());
            statement.setString(3, page.requestedTo().toString());
            statement.setString(4, page.payloadHash());
            statement.setLong(5, page.prices().size());
            statement.setLong(6, acceptedCount);
            statement.setLong(7, rejectedCount);
            statement.executeUpdate();
        }
        Instant detectedAt = Instant.now();
        for (ValidatedPrice price : prices) {
            if (price.accepted()) {
                insertPrice(price.price(), request, detectedAt);
            } else {
                insertExclusions(price, request, runId, detectedAt);
            }
        }
    }

    private List<ValidatedPrice> validate(ImportedPage page) {
        List<ValidatedPrice> validated = new ArrayList<>();
        for (int index = 0; index < page.prices().size(); index++) {
            ImportedPrice price = page.prices().get(index);
            Set<PriceValidationFailure> failures = validator.validate(price);
            if (price != null && price.timestamp() != null
                    && (price.timestamp().isBefore(page.requestedFrom())
                    || !price.timestamp().isBefore(page.requestedTo()))) {
                failures.add(PriceValidationFailure.TIMESTAMP_OUT_OF_RANGE);
            }
            String exclusionTimestamp = price == null || price.timestamp() == null
                    ? missingTimestampIdentity(page, index) : price.timestamp().toString();
            validated.add(new ValidatedPrice(price, exclusionTimestamp, Set.copyOf(failures)));
        }
        return validated;
    }

    private static Set<Instant> coveredTimestamps(List<ValidatedPrice> prices) {
        Set<Instant> timestamps = new LinkedHashSet<>();
        for (ValidatedPrice validated : prices) {
            if (validated.price() != null && validated.price().timestamp() != null) {
                timestamps.add(validated.price().timestamp());
            }
        }
        return timestamps;
    }

    private void enqueueMissingRuns(WorkItem work, HistoryImportRequest request, Set<Instant> observed)
            throws SQLException {
        Instant gapStart = null;
        for (Instant minute = work.fromInclusive(); minute.isBefore(work.toExclusive()); minute = minute.plusSeconds(60)) {
            if (observed.contains(minute)) {
                if (gapStart != null) {
                    insertWork(request, gapStart, minute);
                    gapStart = null;
                }
            } else if (gapStart == null) {
                gapStart = minute;
            }
        }
        if (gapStart != null) {
            insertWork(request, gapStart, work.toExclusive());
        }
    }

    private void insertWork(HistoryImportRequest request, Instant from, Instant to) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO history_import_work (
                    import_run_id, requested_from_utc, requested_to_utc, state)
                VALUES (?, ?, ?, 'PENDING')
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            statement.setString(2, from.toString());
            statement.setString(3, to.toString());
            statement.executeUpdate();
        }
    }

    private void insertClosure(WorkItem work, HistoryImportRequest request, String payloadHash) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO history_import_closure (
                    import_run_id, from_utc, to_utc, provenance, payload_hash)
                VALUES (?, ?, ?, 'CAPITAL_EMPTY_OR_404', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            statement.setString(2, work.fromInclusive().toString());
            statement.setString(3, work.toExclusive().toString());
            statement.setString(4, payloadHash);
            statement.executeUpdate();
        }
    }

    private void requirePending(WorkItem work) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM history_import_work WHERE work_id = ? AND import_run_id = ?")) {
            statement.setLong(1, work.id());
            statement.setString(2, work.importRunId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"PENDING".equals(rows.getString(1))) {
                    throw new IllegalStateException("History interval is no longer pending");
                }
            }
        }
    }

    private void markProcessed(WorkItem work) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE history_import_work SET state = 'PROCESSED', last_error = NULL,
                    last_attempt_utc = ? WHERE work_id = ? AND import_run_id = ? AND state = 'PENDING'
                """)) {
            statement.setString(1, Instant.now().toString());
            statement.setLong(2, work.id());
            statement.setString(3, work.importRunId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("History interval was not pending");
            }
        }
    }

    private boolean minuteCovered(HistoryImportRequest request, Instant timestamp) throws SQLException {
        String sql = """
                SELECT 1 FROM historical_price WHERE source=? AND epic=? AND resolution=? AND snapshot_time_utc=?
                UNION ALL
                SELECT 1 FROM price_exclusion WHERE import_run_id=? AND snapshot_time_utc=? LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.source());
            statement.setString(2, request.epic());
            statement.setString(3, request.resolution());
            statement.setString(4, timestamp.toString());
            statement.setString(5, importRunId(request));
            statement.setString(6, timestamp.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void insertPrice(ImportedPrice price, HistoryImportRequest request, Instant ingestedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PRICE)) {
            int index = 1;
            statement.setString(index++, UUID.randomUUID().toString());
            statement.setString(index++, request.epic());
            statement.setString(index++, request.resolution());
            statement.setString(index++, price.timestamp().toString());
            statement.setBigDecimal(index++, price.openBid());
            statement.setBigDecimal(index++, price.openAsk());
            statement.setBigDecimal(index++, price.highBid());
            statement.setBigDecimal(index++, price.highAsk());
            statement.setBigDecimal(index++, price.lowBid());
            statement.setBigDecimal(index++, price.lowAsk());
            statement.setBigDecimal(index++, price.closeBid());
            statement.setBigDecimal(index++, price.closeAsk());
            statement.setLong(index++, price.lastTradedVolume());
            statement.setString(index++, request.source());
            statement.setString(index, ingestedAt.toString());
            statement.executeUpdate();
        }
    }

    private void insertExclusions(ValidatedPrice invalid, HistoryImportRequest request, String runId,
                                  Instant detectedAt) throws SQLException {
        for (PriceValidationFailure failure : invalid.failures()) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_EXCLUSION)) {
                statement.setString(1, runId);
                statement.setString(2, request.source());
                statement.setString(3, request.epic());
                statement.setString(4, request.resolution());
                statement.setString(5, invalid.exclusionTimestamp());
                statement.setString(6, failure.name());
                statement.setString(7, detectedAt.toString());
                statement.executeUpdate();
            }
        }
    }

    private HistoryCoverage.Assessment coverage(HistoryImportRequest request) {
        if (tableExists("history_import_closure")) {
            return ledgerCoverage(request);
        }
        return legacyPageCoverage(request);
    }

    private HistoryCoverage.Assessment ledgerCoverage(HistoryImportRequest request) {
        List<HistoryCoverage.Page> pages = new ArrayList<>();
        Set<Instant> observed = new HashSet<>();
        try {
            observed.addAll(observedTimestamps(request, request.from(), request.to()));
            for (Instant timestamp : observed) {
                pages.add(new HistoryCoverage.Page(timestamp, timestamp.plusSeconds(60), List.of(timestamp), false));
            }
            String sql = """
                    SELECT c.from_utc, c.to_utc, c.provenance, c.payload_hash,
                        p.payload_hash, p.received_count, p.accepted_count, p.rejected_count
                    FROM history_import_closure c
                    LEFT JOIN history_import_page p
                      ON p.import_run_id = c.import_run_id
                     AND p.requested_from_utc = c.from_utc AND p.requested_to_utc = c.to_utc
                    WHERE c.import_run_id = ? ORDER BY c.from_utc
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, importRunId(request));
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Instant from = Instant.parse(rows.getString(1));
                        Instant to = Instant.parse(rows.getString(2));
                        String provenance = rows.getString(3);
                        String closureHash = rows.getString(4);
                        String pageHash = rows.getString(5);
                        if (!"CAPITAL_EMPTY_OR_404".equals(provenance)) {
                            throw new IllegalStateException("Unsupported closure provenance: " + provenance);
                        }
                        if (closureHash == null || !closureHash.equals(pageHash)
                                || rows.getLong(6) != 0 || rows.getLong(7) != 0 || rows.getLong(8) != 0) {
                            throw new IllegalStateException("Closure has no matching exact empty provider observation");
                        }
                        if (from.isBefore(request.from()) || to.isAfter(request.to()) || !from.isBefore(to)) {
                            throw new IllegalStateException("Closure interval is outside the import request");
                        }
                        pages.add(new HistoryCoverage.Page(from, to, List.of(), true, provenance, closureHash));
                    }
                }
            }
            return HistoryCoverage.assess(request.from(), request.to(), pages);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit closure-ledger coverage", exception);
        }
    }

    private HistoryCoverage.Assessment legacyPageCoverage(HistoryImportRequest request) {
        String sql = """
                SELECT requested_from_utc, requested_to_utc, received_count FROM history_import_page
                WHERE import_run_id = ? ORDER BY requested_from_utc
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                List<HistoryCoverage.Page> pages = new ArrayList<>();
                while (rows.next()) {
                    Instant from = Instant.parse(rows.getString(1));
                    Instant to = Instant.parse(rows.getString(2));
                    pages.add(new HistoryCoverage.Page(from, to, observedTimestamps(request, from, to),
                            rows.getLong(3) == 0));
                }
                return HistoryCoverage.assess(request.from(), request.to(), pages);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit import coverage", exception);
        }
    }

    private List<Instant> observedTimestamps(HistoryImportRequest request, Instant from, Instant to)
            throws SQLException {
        Set<Instant> timestamps = new HashSet<>();
        String prices = """
                SELECT snapshot_time_utc FROM historical_price
                WHERE source=? AND epic=? AND resolution=? AND snapshot_time_utc>=? AND snapshot_time_utc<?
                """;
        try (PreparedStatement statement = connection.prepareStatement(prices)) {
            statement.setString(1, request.source());
            statement.setString(2, request.epic());
            statement.setString(3, request.resolution());
            statement.setString(4, from.toString());
            statement.setString(5, to.toString());
            collectTimestamps(statement, timestamps);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_time_utc FROM price_exclusion
                WHERE import_run_id=? AND snapshot_time_utc>=? AND snapshot_time_utc<?
                """)) {
            statement.setString(1, importRunId(request));
            statement.setString(2, from.toString());
            statement.setString(3, to.toString());
            collectTimestamps(statement, timestamps);
        }
        return List.copyOf(timestamps);
    }

    private static void collectTimestamps(PreparedStatement statement, Set<Instant> timestamps) throws SQLException {
        try (var rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    timestamps.add(Instant.parse(rows.getString(1)));
                } catch (java.time.format.DateTimeParseException ignored) {
                    // Missing timestamps remain auditable exclusions but cannot establish minute coverage.
                }
            }
        }
    }

    private long excludedMinuteCount(HistoryImportRequest request) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT snapshot_time_utc) FROM price_exclusion WHERE import_run_id=?")) {
            statement.setString(1, importRunId(request));
            return singleLong(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count excluded prices", exception);
        }
    }

    private PageCounts pageCounts(HistoryImportRequest request) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*), COALESCE(SUM(received_count),0), COALESCE(SUM(accepted_count),0),
                    COALESCE(SUM(rejected_count),0) FROM history_import_page WHERE import_run_id=?
                """)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                return new PageCounts(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count raw history observations", exception);
        }
    }

    private Map<String, Long> exclusionsByReason(HistoryImportRequest request) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reason, COUNT(*) FROM price_exclusion WHERE import_run_id=? GROUP BY reason ORDER BY reason
                """)) {
            statement.setString(1, importRunId(request));
            try (var rows = statement.executeQuery()) {
                Map<String, Long> counts = new LinkedHashMap<>();
                while (rows.next()) {
                    counts.put(rows.getString(1), rows.getLong(2));
                }
                return counts;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not group price exclusions", exception);
        }
    }

    private Instant[] actualBounds(HistoryImportRequest request) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT MIN(snapshot_time_utc), MAX(snapshot_time_utc) FROM historical_price
                WHERE source=? AND epic=? AND resolution=? AND snapshot_time_utc>=? AND snapshot_time_utc<?
                """)) {
            setRequestIdentity(statement, request);
            try (var rows = statement.executeQuery()) {
                String first = rows.getString(1);
                String last = rows.getString(2);
                return new Instant[]{first == null ? null : Instant.parse(first),
                        last == null ? null : Instant.parse(last)};
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit actual price bounds", exception);
        }
    }

    private long malformedAcceptedCount(HistoryImportRequest request) {
        String sql = """
                SELECT COUNT(*) FROM historical_price
                WHERE source=? AND epic=? AND resolution=? AND snapshot_time_utc>=? AND snapshot_time_utc<?
                  AND (open_bid<=0 OR open_ask<=0 OR high_bid<=0 OR high_ask<=0 OR low_bid<=0 OR low_ask<=0
                    OR close_bid<=0 OR close_ask<=0 OR open_bid>open_ask OR high_bid>high_ask
                    OR low_bid>low_ask OR close_bid>close_ask)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setRequestIdentity(statement, request);
            return singleLong(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not audit accepted price validity", exception);
        }
    }

    private ExistingPage existingPage(ImportedPage page, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_hash, received_count, accepted_count, rejected_count FROM history_import_page
                WHERE import_run_id=? AND requested_from_utc=? AND requested_to_utc=?
                """)) {
            statement.setString(1, runId);
            statement.setString(2, page.requestedFrom().toString());
            statement.setString(3, page.requestedTo().toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? new ExistingPage(rows.getString(1), rows.getLong(2), rows.getLong(3),
                        rows.getLong(4)) : null;
            }
        }
    }

    private boolean tableExists(String name) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect staging schema", exception);
        }
    }

    private static void validatePageBounds(ImportedPage page, HistoryImportRequest request) {
        if (page.requestedFrom().isBefore(request.from()) || page.requestedTo().isAfter(request.to())
                || !page.requestedFrom().isBefore(page.requestedTo())) {
            throw new IllegalArgumentException("Page bounds must be within the import range");
        }
    }

    private static String importRunId(HistoryImportRequest request) {
        String identity = String.join("\n", request.source(), request.epic(), request.resolution(),
                request.from().toString(), request.to().toString());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String missingTimestampIdentity(ImportedPage page, int index) {
        return "MISSING_TIMESTAMP:" + page.requestedFrom() + ':' + page.requestedTo() + ':'
                + page.payloadHash() + ':' + index;
    }

    private static void applySchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
                      snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
                      high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL, low_ask float NOT NULL,
                      close_bid float NOT NULL, close_ask float NOT NULL, last_traded_volume integer NOT NULL,
                      source varchar(255), ingestion_time_utc timestamp)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
                    ON historical_price (source, epic, resolution, snapshot_time_utc)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS price_exclusion (
                      import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
                      snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
                      PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS price_exclusion_epic_resolution_timestamp
                    ON price_exclusion (epic, resolution, snapshot_time_utc)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS history_import_page (
                      import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
                      payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
                      rejected_count INTEGER NOT NULL,
                      PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS history_import_run (
                      import_run_id TEXT PRIMARY KEY, algorithm_version INTEGER NOT NULL, source TEXT NOT NULL,
                      epic TEXT NOT NULL, resolution TEXT NOT NULL, requested_from_utc TEXT NOT NULL,
                      requested_to_utc TEXT NOT NULL, created_at_utc TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS history_import_work (
                      work_id INTEGER PRIMARY KEY AUTOINCREMENT, import_run_id TEXT NOT NULL,
                      requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (state IN ('PENDING','PROCESSED')), last_error TEXT,
                      last_attempt_utc TEXT,
                      UNIQUE (import_run_id, requested_from_utc, requested_to_utc),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS history_import_closure (
                      import_run_id TEXT NOT NULL, from_utc TEXT NOT NULL, to_utc TEXT NOT NULL,
                      provenance TEXT NOT NULL, payload_hash TEXT NOT NULL,
                      PRIMARY KEY (import_run_id, from_utc, to_utc),
                      FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id))
                    """);
        }
    }

    private static void setRequestIdentity(PreparedStatement statement, HistoryImportRequest request)
            throws SQLException {
        statement.setString(1, request.source());
        statement.setString(2, request.epic());
        statement.setString(3, request.resolution());
        statement.setString(4, request.from().toString());
        statement.setString(5, request.to().toString());
    }

    private static long singleLong(PreparedStatement statement) throws SQLException {
        try (var rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Count query returned no row");
            }
            return rows.getLong(1);
        }
    }

    private void requireStageOwner() {
        if (stageRunId == null || lease == null) {
            throw new IllegalStateException("Pending work requires an active stage owner");
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not restore SQLite auto-commit", exception);
        }
    }

    private record ValidatedPrice(ImportedPrice price, String exclusionTimestamp,
                                  Set<PriceValidationFailure> failures) {
        private boolean accepted() {
            return failures.isEmpty();
        }
    }

    private record ExistingPage(String payloadHash, long receivedCount, long acceptedCount, long rejectedCount) {
        private boolean matches(String hash, long received, long accepted, long rejected) {
            return payloadHash.equals(hash) && receivedCount == received && acceptedCount == accepted
                    && rejectedCount == rejected;
        }
    }

    private record PageCounts(long observationCount, long receivedCount, long acceptedCount, long rejectedCount) { }

    private static final class StageLease implements AutoCloseable {
        private final Path databasePath;
        private final FileChannel channel;
        private final FileLock lock;

        private StageLease(Path databasePath, FileChannel channel, FileLock lock) {
            this.databasePath = databasePath;
            this.channel = channel;
            this.lock = lock;
        }

        private static StageLease acquire(Path database) throws IOException {
            Path realPath = database.toRealPath();
            requireSafeLinkIdentity(realPath, database);
            Path lockPath = realPath.resolveSibling(realPath.getFileName() + ".stage.lock");
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IllegalStateException("History staging is already active for " + database);
                }
                requireSafeLinkIdentity(realPath, database);
                return new StageLease(realPath, channel, lock);
            } catch (OverlappingFileLockException exception) {
                closeAfterAcquireFailure(channel, exception);
                throw new IllegalStateException("History staging is already active for " + database, exception);
            } catch (IOException | RuntimeException exception) {
                closeAfterAcquireFailure(channel, exception);
                throw exception;
            }
        }

        private static void requireSafeLinkIdentity(Path realPath, Path requestedPath) throws IOException {
            if (!Files.getFileStore(realPath).supportsFileAttributeView("unix")) {
                throw new IllegalStateException(
                        "Refusing unsafe staging filesystem without Unix file attributes: " + requestedPath);
            }
            Object rawLinkCount;
            try {
                rawLinkCount = Files.getAttribute(realPath, "unix:nlink");
            } catch (IllegalArgumentException | UnsupportedOperationException exception) {
                throw new IllegalStateException(
                        "Refusing unsafe staging filesystem without a reliable link count: " + requestedPath,
                        exception);
            }
            if (!(rawLinkCount instanceof Number linkCount)) {
                throw new IllegalStateException(
                        "Refusing unsafe staging filesystem with a non-numeric link count: " + requestedPath);
            }
            if (linkCount.longValue() != 1L) {
                throw new IllegalStateException(
                        "Refusing hard-linked staging database before provider work: " + requestedPath);
            }
        }

        private Path databasePath() {
            return databasePath;
        }

        private static void closeAfterAcquireFailure(FileChannel channel, Exception failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        @Override
        public void close() {
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw new IllegalStateException("Could not release history staging lease", failure);
            }
        }
    }
}
