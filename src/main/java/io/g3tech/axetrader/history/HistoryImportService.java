package io.g3tech.axetrader.history;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class HistoryImportService {

    private static final int MAX_BARS_PER_PAGE = 1_000;
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final HistoricalPricePageSource pageSource;
    private final HistoryDatabasePromoter promoter;
    private final PriceValidator validator = new PriceValidator();

    public HistoryImportService(HistoricalPricePageSource pageSource, HistoryDatabasePromoter promoter) {
        this.pageSource = Objects.requireNonNull(pageSource, "pageSource");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
    }

    public HistoryImportAudit probe(HistoryImportRequest request) {
        Objects.requireNonNull(request, "request");
        ImportedPage page = fetch(request, request.from());
        return auditProbe(page, request);
    }

    public HistoryImportAudit stage(HistoryImportRequest request) {
        Objects.requireNonNull(request, "request");
        Path staging = request.stagingDatabase();
        if (Files.exists(staging)) {
            throw new IllegalStateException("Refusing to stage into an existing database: " + staging);
        }

        Instant cursor = request.from();
        HistoryImportAudit audit;
        try (HistoryStagingStore store = HistoryStagingStore.open(staging)) {
            while (cursor.isBefore(request.to())) {
                ImportedPage page = fetch(request, cursor);
                Instant next = nextCursor(page, cursor, request.to());
                store.writePage(page, request);
                cursor = next;
            }
            audit = store.audit(request);
        }
        HistoryDatabasePromoter.requirePromotableAudit(audit);
        recordCompletion(request, audit);
        return audit;
    }

    public Path promote(HistoryImportRequest request, Path activeDatabase, Path archive) {
        Objects.requireNonNull(request, "request");
        CompletedImport completion = discoverCompletedImport(request.stagingDatabase());
        if (!sameImport(completion.request(), request)) {
            throw new IllegalStateException("Configured request does not match the completed staged import");
        }
        HistoryImportAudit audit;
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            audit = store.audit(request);
        }
        if (!completion.auditFingerprint().equals(auditFingerprint(audit))) {
            throw new IllegalStateException("Completed staged import no longer matches its audit");
        }
        return promoter.promote(request.stagingDatabase(), activeDatabase, archive, audit);
    }

    public Path promote(Path stagingDatabase, Path activeDatabase, Path archive) {
        CompletedImport completion = discoverCompletedImport(stagingDatabase);
        return promote(completion.request(), activeDatabase, archive);
    }

    private ImportedPage fetch(HistoryImportRequest request, Instant cursor) {
        ImportedPage page = Objects.requireNonNull(
                pageSource.fetch(request, cursor, request.to(), MAX_BARS_PER_PAGE),
                "Historical price source returned no page");
        if (!page.requestedFrom().equals(cursor) || !page.requestedTo().equals(request.to())) {
            throw new IllegalStateException("Historical price source returned unexpected page bounds");
        }
        return page;
    }

    private static Instant nextCursor(ImportedPage page, Instant cursor, Instant requestedTo) {
        if (page.prices().isEmpty()) {
            throw new IllegalStateException("Historical price source returned an empty page before the import completed");
        }
        Instant greatestTimestamp = page.prices().stream()
                .filter(Objects::nonNull)
                .map(ImportedPrice::timestamp)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("Historical price page cannot advance without a timestamp"));
        if (greatestTimestamp.isBefore(cursor) || !greatestTimestamp.isBefore(requestedTo)) {
            throw new IllegalStateException("Historical price page did not advance within its half-open bounds");
        }
        Instant next = greatestTimestamp.plus(MINUTE);
        if (!next.isAfter(cursor)) {
            throw new IllegalStateException("Historical price page did not advance the import cursor");
        }
        return next;
    }

    private HistoryImportAudit auditProbe(ImportedPage page, HistoryImportRequest request) {
        List<ImportedPrice> accepted = new ArrayList<>();
        Map<String, Long> exclusions = new LinkedHashMap<>();
        long rejected = 0;
        for (ImportedPrice price : page.prices()) {
            Set<PriceValidationFailure> failures = validator.validate(price);
            if (price != null && price.timestamp() != null
                    && (price.timestamp().isBefore(request.from()) || !price.timestamp().isBefore(request.to()))) {
                failures.add(PriceValidationFailure.TIMESTAMP_OUT_OF_RANGE);
            }
            if (failures.isEmpty()) {
                accepted.add(price);
            } else {
                rejected++;
                failures.forEach(failure -> exclusions.merge(failure.name(), 1L, Long::sum));
            }
        }

        List<Instant> timestamps = accepted.stream().map(ImportedPrice::timestamp).toList();
        Set<Instant> distinctTimestamps = new LinkedHashSet<>(timestamps);
        long duplicateCount = timestamps.size() - distinctTimestamps.size();
        Instant actualFrom = distinctTimestamps.stream().min(Instant::compareTo).orElse(null);
        Instant actualTo = distinctTimestamps.stream().max(Instant::compareTo).orElse(null);
        return new HistoryImportAudit(
                request.from(), request.to(), actualFrom, actualTo,
                page.prices().size(), accepted.size(), rejected,
                distinctTimestamps.size(), rejected, duplicateCount, exclusions, duplicateCount == 0);
    }

    private static CompletedImport discoverCompletedImport(Path stagingDatabase) {
        Objects.requireNonNull(stagingDatabase, "stagingDatabase");
        if (!Files.isRegularFile(stagingDatabase)) {
            throw new IllegalStateException("Staging database does not exist: " + stagingDatabase);
        }
        String url = "jdbc:sqlite:file:" + stagingDatabase.toAbsolutePath().normalize() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url)) {
            String sql = """
                    SELECT source, epic, resolution, requested_from_utc, requested_to_utc, audit_fingerprint
                    FROM history_import_completion
                    """;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw new IllegalStateException("Staging database has no completed import");
                }
                HistoryImportRequest request = new HistoryImportRequest(
                        rows.getString("epic"), rows.getString("resolution"),
                        Instant.parse(rows.getString("requested_from_utc")),
                        Instant.parse(rows.getString("requested_to_utc")),
                        stagingDatabase, rows.getString("source"));
                CompletedImport completed = new CompletedImport(request, rows.getString("audit_fingerprint"));
                if (rows.next()) {
                    throw new IllegalStateException("Staging database contains more than one completed import");
                }
                return completed;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Staging database is not a completed history import", exception);
        }
    }

    private static void recordCompletion(HistoryImportRequest request, HistoryImportAudit audit) {
        String create = """
                CREATE TABLE history_import_completion (
                    completion_id INTEGER PRIMARY KEY CHECK (completion_id = 1),
                    source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
                    requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
                    audit_fingerprint TEXT NOT NULL, completed_at_utc TEXT NOT NULL
                )
                """;
        String insert = """
                INSERT INTO history_import_completion (
                    completion_id, source, epic, resolution, requested_from_utc, requested_to_utc,
                    audit_fingerprint, completed_at_utc)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + request.stagingDatabase().toAbsolutePath().normalize())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(create);
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, request.source());
                statement.setString(2, request.epic());
                statement.setString(3, request.resolution());
                statement.setString(4, request.from().toString());
                statement.setString(5, request.to().toString());
                statement.setString(6, auditFingerprint(audit));
                statement.setString(7, Instant.now().toString());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not mark staged history import as completed", exception);
        }
    }

    private static String auditFingerprint(HistoryImportAudit audit) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(audit.requestedFrom()).append('\n')
                .append(audit.requestedTo()).append('\n')
                .append(audit.actualFrom()).append('\n')
                .append(audit.actualTo()).append('\n')
                .append(audit.receivedCount()).append('\n')
                .append(audit.acceptedCount()).append('\n')
                .append(audit.rejectedCount()).append('\n')
                .append(audit.acceptedMinuteCount()).append('\n')
                .append(audit.excludedMinuteCount()).append('\n')
                .append(audit.duplicateCount()).append('\n');
        audit.exclusionsByReason().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        canonical.append(audit.isConsistent());
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean sameImport(HistoryImportRequest first, HistoryImportRequest second) {
        return first.source().equals(second.source())
                && first.epic().equals(second.epic())
                && first.resolution().equals(second.resolution())
                && first.from().equals(second.from())
                && first.to().equals(second.to())
                && first.stagingDatabase().toAbsolutePath().normalize()
                .equals(second.stagingDatabase().toAbsolutePath().normalize());
    }

    private record CompletedImport(HistoryImportRequest request, String auditFingerprint) {
    }
}
