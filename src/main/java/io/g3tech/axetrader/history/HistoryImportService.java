package io.g3tech.axetrader.history;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class HistoryImportService {

    private static final int MAX_BARS_PER_PAGE = 1_000;
    private static final Duration CAPITAL_PAGE_WINDOW = Duration.ofMinutes(MAX_BARS_PER_PAGE - 1L);

    private final HistoricalPricePageSource pageSource;
    private final HistoryDatabasePromoter promoter;
    private final PriceValidator validator = new PriceValidator();

    public HistoryImportService(HistoricalPricePageSource pageSource, HistoryDatabasePromoter promoter) {
        this.pageSource = Objects.requireNonNull(pageSource, "pageSource");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
    }

    public HistoryImportAudit probe(HistoryImportRequest request, Path activeDatabase, Path archive) {
        Objects.requireNonNull(request, "request");
        validatePaths(request.stagingDatabase(), activeDatabase, archive);
        ImportedPage page = fetch(request, request.from(), boundedTo(request.from(), request.to()));
        return auditProbe(page, request);
    }

    public HistoryImportAudit probe(HistoryImportRequest request) {
        return probe(request, Path.of("data", "axe-trader.sqlite"),
                Path.of("data", "axe-trader.sqlite.gz"));
    }

    public HistoryImportAudit stage(HistoryImportRequest request, Path activeDatabase, Path archive) {
        Objects.requireNonNull(request, "request");
        Path staging = request.stagingDatabase();
        validatePaths(staging, activeDatabase, archive);
        if (Files.exists(staging)) {
            throw new IllegalStateException("Refusing to stage into an existing database: " + staging);
        }

        Instant cursor = request.from();
        HistoryImportAudit audit;
        try (HistoryStagingStore store = HistoryStagingStore.open(staging)) {
            while (cursor.isBefore(request.to())) {
                Instant pageTo = boundedTo(cursor, request.to());
                ImportedPage page = fetch(request, cursor, pageTo);
                store.writePage(page, request);
                cursor = pageTo;
            }
            audit = store.audit(request);
        }
        HistoryDatabasePromoter.requirePromotableAudit(audit);
        recordCompletion(request, audit);
        return audit;
    }

    public HistoryImportAudit stage(HistoryImportRequest request) {
        return stage(request, Path.of("data", "axe-trader.sqlite"),
                Path.of("data", "axe-trader.sqlite.gz"));
    }

    public Path promote(HistoryImportRequest request, Path activeDatabase, Path archive) {
        Objects.requireNonNull(request, "request");
        validatePaths(request.stagingDatabase(), activeDatabase, archive);
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
        validatePaths(stagingDatabase, activeDatabase, archive);
        CompletedImport completion = discoverCompletedImport(stagingDatabase);
        return promote(completion.request(), activeDatabase, archive);
    }

    static void validatePaths(Path stagingDatabase, Path activeDatabase, Path archive) {
        Path staging = canonicalIdentity(stagingDatabase, "stagingDatabase");
        Path active = canonicalIdentity(activeDatabase, "activeDatabase");
        Path gzArchive = canonicalIdentity(archive, "archive");
        rejectSamePath(staging, active);
        rejectSamePath(staging, gzArchive);
        rejectSamePath(active, gzArchive);
    }

    private ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive) {
        ImportedPage page = Objects.requireNonNull(
                pageSource.fetch(request, fromInclusive, toExclusive, MAX_BARS_PER_PAGE),
                "Historical price source returned no page");
        if (!page.requestedFrom().equals(fromInclusive) || !page.requestedTo().equals(toExclusive)) {
            throw new IllegalStateException("Historical price source returned unexpected page bounds");
        }
        List<ImportedPrice> normalized = new ArrayList<>();
        for (ImportedPrice price : page.prices()) {
            if (price != null && price.timestamp() != null) {
                if (price.timestamp().equals(toExclusive)) {
                    continue;
                }
                if (price.timestamp().isBefore(fromInclusive) || !price.timestamp().isBefore(toExclusive)) {
                    throw new IllegalStateException("Historical price source returned a timestamp outside its half-open page bounds");
                }
            }
            normalized.add(price);
        }
        return new ImportedPage(fromInclusive, toExclusive, normalized, page.payloadHash());
    }

    private static Instant boundedTo(Instant fromInclusive, Instant requestedTo) {
        Instant pageLimit = fromInclusive.plus(CAPITAL_PAGE_WINDOW);
        return pageLimit.isBefore(requestedTo) ? pageLimit : requestedTo;
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
        HistoryCoverage.Assessment coverage = HistoryCoverage.assess(request.from(), request.to(), List.of(
                new HistoryCoverage.Page(page.requestedFrom(), page.requestedTo(), timestamps)));
        return new HistoryImportAudit(
                request.from(), request.to(), actualFrom, actualTo,
                page.prices().size(), accepted.size(), rejected,
                distinctTimestamps.size(), rejected, duplicateCount, exclusions,
                coverage.recognizedSessionClosures(), coverage.continuityGaps(),
                duplicateCount == 0 && coverage.continuityGaps().isEmpty());
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
                .append(audit.duplicateCount()).append('\n')
                .append(audit.recognizedSessionClosures()).append('\n')
                .append(audit.continuityGaps()).append('\n');
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

    private static Path normalized(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static Path canonicalIdentity(Path path, String name) {
        try {
            return canonicalIdentity(normalized(path, name), new HashSet<>());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not resolve history import path " + path, exception);
        }
    }

    private static Path canonicalIdentity(Path path, Set<Path> visited) throws java.io.IOException {
        if (!visited.add(path)) {
            throw new java.io.IOException("Symbolic-link cycle while resolving " + path);
        }
        if (Files.isSymbolicLink(path)) {
            Path target = Files.readSymbolicLink(path);
            Path resolved = target.isAbsolute() ? target : path.getParent().resolve(target);
            return canonicalIdentity(resolved.toAbsolutePath().normalize(), visited);
        }

        ArrayDeque<Path> missingNames = new ArrayDeque<>();
        Path existingAncestor = path;
        while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            missingNames.addFirst(existingAncestor.getFileName());
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            return path;
        }
        Path canonicalAncestor = Files.isSymbolicLink(existingAncestor)
                ? canonicalIdentity(existingAncestor, visited)
                : existingAncestor.toRealPath();
        for (Path missingName : missingNames) {
            canonicalAncestor = canonicalAncestor.resolve(missingName);
        }
        return canonicalAncestor.normalize();
    }

    private static void rejectSamePath(Path first, Path second) {
        if (first.equals(second)) {
            throw new IllegalArgumentException("Staging, active, and archive paths must be different");
        }
        if (!Files.exists(first) || !Files.exists(second)) {
            return;
        }
        try {
            if (Files.isSameFile(first, second)) {
                throw new IllegalArgumentException("Staging, active, and archive paths must be different");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not verify history import path isolation", exception);
        }
    }

    private record CompletedImport(HistoryImportRequest request, String auditFingerprint) {
    }
}
