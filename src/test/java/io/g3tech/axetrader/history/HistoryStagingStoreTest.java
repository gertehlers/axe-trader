package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryStagingStoreTest {

    private static final Instant FROM = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2024-01-01T00:03:00Z");

    @TempDir
    Path tempDir;

    @Test
    void rejectsCrossedCloseAndRecordsItsMinute() {
        HistoryImportRequest request = request();
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(page(price("2024-01-01T00:01:00Z", "4800.3", "4800.1")), request);

            assertThat(store.countAccepted(request)).isZero();
            assertThat(store.exclusions(request))
                    .extracting(HistoryStagingStore.PriceExclusion::reason)
                    .containsExactly("CLOSE_BID_ABOVE_ASK");
        }
    }

    @Test
    void writesAcceptedPricesAndMakesDuplicatePagesIdempotent() {
        HistoryImportRequest request = request();
        ImportedPage page = page(price("2024-01-01T00:01:00Z", "4800.1", "4800.3"));
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(page, request);
            store.writePage(page, request);

            assertThat(store.countAccepted(request)).isEqualTo(1);
            assertThat(store.exclusions(request)).isEmpty();
        }
    }

    @Test
    void recordsMissingTimestampAsAnAuditableExclusion() {
        HistoryImportRequest request = request();
        ImportedPrice missingTimestamp = new ImportedPrice(
                null,
                new BigDecimal("4800.1"), new BigDecimal("4800.3"),
                new BigDecimal("4801.1"), new BigDecimal("4801.3"),
                new BigDecimal("4799.1"), new BigDecimal("4799.3"),
                new BigDecimal("4800.1"), new BigDecimal("4800.3"), 123L);
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(page(missingTimestamp), request);

            assertThat(store.countAccepted(request)).isZero();
            assertThat(store.exclusions(request)).singleElement().satisfies(exclusion -> {
                assertThat(exclusion.reason()).isEqualTo("TIMESTAMP_MISSING");
                assertThat(exclusion.snapshotTimeUtc()).startsWith("MISSING_TIMESTAMP:");
            });
            HistoryImportAudit audit = store.audit(request);
            assertThat(audit.receivedCount()).isEqualTo(1);
            assertThat(audit.rejectedCount()).isEqualTo(1);
            assertThat(audit.excludedMinuteCount()).isEqualTo(1);
            assertThat(audit.isConsistent()).isFalse();
        }
    }

    @Test
    void rejectsSameBoundsWithDifferentPayloadHash() {
        HistoryImportRequest request = request();
        ImportedPage original = page("first-payload", price("2024-01-01T00:01:00Z", "4800.1", "4800.3"));
        ImportedPage changed = page("changed-payload", price("2024-01-01T00:01:00Z", "4800.1", "4800.3"));
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(original, request);

            assertThatThrownBy(() -> store.writePage(changed, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different payload");
            assertThat(store.countAccepted(request)).isEqualTo(1);
        }
    }

    @Test
    void auditSeparatesAcceptedAndExcludedMinutes() {
        HistoryImportRequest request = request();
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(page(
                    price("2024-01-01T00:01:00Z", "4800.1", "4800.3"),
                    price("2024-01-01T00:02:00Z", "4800.1", "4800.3"),
                    priceWithCrossedHigh("2024-01-01T00:00:00Z")), request);

            HistoryImportAudit audit = store.audit(request);

            assertThat(audit.requestedFrom()).isEqualTo(FROM);
            assertThat(audit.requestedTo()).isEqualTo(TO);
            assertThat(audit.actualFrom()).isEqualTo(Instant.parse("2024-01-01T00:01:00Z"));
            assertThat(audit.actualTo()).isEqualTo(Instant.parse("2024-01-01T00:02:00Z"));
            assertThat(audit.receivedCount()).isEqualTo(3);
            assertThat(audit.acceptedCount()).isEqualTo(2);
            assertThat(audit.rejectedCount()).isEqualTo(1);
            assertThat(audit.acceptedMinuteCount()).isEqualTo(2);
            assertThat(audit.excludedMinuteCount()).isEqualTo(1);
            assertThat(audit.duplicateCount()).isZero();
            assertThat(audit.exclusionsByReason()).containsEntry("HIGH_BID_ABOVE_ASK", 1L);
            assertThat(audit.isConsistent()).isTrue();
        }
    }

    @Test
    void validatesEachPriceAgainstItsOwnHalfOpenPageBounds() {
        HistoryImportRequest request = request();
        ImportedPage laterPage = new ImportedPage(
                Instant.parse("2024-01-01T00:01:00Z"), TO,
                List.of(price("2024-01-01T00:00:00Z", "4800.1", "4800.3")), "later-page");
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(laterPage, request);

            assertThat(store.countAccepted(request)).isZero();
            assertThat(store.exclusions(request))
                    .extracting(HistoryStagingStore.PriceExclusion::reason)
                    .containsExactly("TIMESTAMP_OUT_OF_RANGE");
        }
    }

    @Test
    void overlappingRawObservationsDoNotInflateDistinctAuditTotals() {
        HistoryImportRequest request = request();
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(page(
                    price("2024-01-01T00:00:00Z", "4800.1", "4800.3"),
                    price("2024-01-01T00:01:00Z", "4800.1", "4800.3"),
                    price("2024-01-01T00:02:00Z", "4800.1", "4800.3")), request);
            store.writePage(new ImportedPage(FROM.plusSeconds(60), TO,
                    List.of(price("2024-01-01T00:01:00Z", "4800.1", "4800.3")), "overlap"), request);

            HistoryImportAudit audit = store.audit(request);

            assertThat(audit.receivedCount()).isEqualTo(3);
            assertThat(audit.acceptedCount()).isEqualTo(3);
            assertThat(audit.observationCount()).isEqualTo(2);
            assertThat(audit.rawReceivedCount()).isEqualTo(4);
            assertThat(audit.acceptedMinuteCount()).isEqualTo(3);
            assertThat(audit.duplicateCount()).isZero();
            assertThat(audit.isConsistent()).isTrue();
        }
    }

    @Test
    void acceptedExcludedOverlapFailsExactNonOverlappingCoverageAudit() {
        HistoryImportRequest request = request();
        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            store.writePage(page(
                    price("2024-01-01T00:00:00Z", "4800.1", "4800.3"),
                    price("2024-01-01T00:01:00Z", "4800.1", "4800.3"),
                    price("2024-01-01T00:02:00Z", "4800.1", "4800.3")), request);
            store.writePage(new ImportedPage(FROM.plusSeconds(60), TO,
                    List.of(price("2024-01-01T00:01:00Z", "4800.3", "4800.1")), "conflict"), request);

            HistoryImportAudit audit = store.audit(request);

            assertThat(audit.continuityGaps()).isEmpty();
            assertThat(audit.acceptedMinuteCount()).isEqualTo(3);
            assertThat(audit.excludedMinuteCount()).isEqualTo(1);
            assertThat(audit.isConsistent()).isFalse();
        }
    }

    @Test
    void auditRejectsClosureWithoutExactPersistedProviderObservation() throws Exception {
        HistoryImportRequest request = request();
        try (HistoryStagingStore store = HistoryStagingStore.openForStage(
                request.stagingDatabase(), request, Duration.ofMinutes(999))) {
            HistoryStagingStore.WorkItem work = store.nextPending();
            store.process(work, new ImportedPage(FROM, TO, List.of(), "empty-hash"), request);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase())) {
            connection.createStatement().executeUpdate(
                    "UPDATE history_import_closure SET provenance='SYNTHESIZED'");
        }

        try (HistoryStagingStore store = HistoryStagingStore.open(request.stagingDatabase())) {
            assertThatThrownBy(() -> store.audit(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closure provenance");
        }
    }

    @Test
    void secondStageOwnerIsRefusedBeforeWorkCanBeFetched() {
        HistoryImportRequest request = request();
        try (HistoryStagingStore first = HistoryStagingStore.openForStage(
                request.stagingDatabase(), request, Duration.ofMinutes(999))) {
            assertThatThrownBy(() -> {
                try (HistoryStagingStore ignored = HistoryStagingStore.openForStage(
                        request.stagingDatabase(), request, Duration.ofMinutes(999))) {
                    // A second owner must never reach provider work.
                }
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already active");
        }
    }

    @Test
    void symlinkAliasCannotOwnPendingWorkConcurrently() throws Exception {
        Path database = request().stagingDatabase();
        initializePendingStage(database);
        Path alias = tempDir.resolve("staging-symlink.sqlite");
        Files.createSymbolicLink(alias, database);

        assertAliasCannotOwnPendingWork(database, alias);
    }

    @Test
    void hardLinkCreatedDuringOwnershipIsRefusedBeforeWork() throws Exception {
        Path database = request().stagingDatabase();
        initializePendingStage(database);
        Path alias = tempDir.resolve("staging-hard-link.sqlite");
        LeaseProcess owner = startLeaseProcess(database, "hard-link-owner", false);
        LeaseProcess contender = null;
        try {
            awaitAnySignal(owner, owner.work(), owner.error());
            assertThat(owner.error()).doesNotExist();
            assertThat(owner.work()).exists();
            Files.createLink(alias, database);

            contender = startLeaseProcess(alias, "hard-link-contender", false);
            awaitAnySignal(contender, contender.unsafe(), contender.work(), contender.error());
            assertThat(contender.error()).doesNotExist();
            assertThat(contender.unsafe()).exists();
            assertThat(contender.work()).doesNotExist();
        } finally {
            release(contender);
            release(owner);
        }
    }

    @Test
    void databaseWithExistingHardLinkIsRefusedBeforeWork() throws Exception {
        Path database = request().stagingDatabase();
        initializePendingStage(database);
        Path alias = tempDir.resolve("existing-hard-link.sqlite");
        Files.createLink(alias, database);
        LeaseProcess original = startLeaseProcess(database, "hard-link-original", false);
        LeaseProcess linked = startLeaseProcess(alias, "hard-link-alias", false);
        try {
            awaitAnySignal(original, original.unsafe(), original.work(), original.error());
            awaitAnySignal(linked, linked.unsafe(), linked.work(), linked.error());
            assertThat(original.error()).doesNotExist();
            assertThat(linked.error()).doesNotExist();
            assertThat(original.unsafe()).exists();
            assertThat(linked.unsafe()).exists();
            assertThat(original.work()).doesNotExist();
            assertThat(linked.work()).doesNotExist();
        } finally {
            release(linked);
            release(original);
        }
    }

    @Test
    void leaseHandoffKeepsOneUnderlyingDatabaseOwner() throws Exception {
        Path database = request().stagingDatabase();
        initializePendingStage(database);
        Path nextSymlink = tempDir.resolve("handoff-next-symlink.sqlite");
        Path contenderSymlink = tempDir.resolve("handoff-contender-symlink.sqlite");
        Files.createSymbolicLink(nextSymlink, database);
        Files.createSymbolicLink(contenderSymlink, database);
        LeaseProcess owner = startLeaseProcess(database, "owner", false);
        LeaseProcess nextOwner = null;
        LeaseProcess contender = null;
        try {
            awaitAnySignal(owner, owner.work(), owner.error());
            assertThat(owner.error()).doesNotExist();
            assertThat(owner.work()).exists();
            Path stableLock = onlyLeaseFile();
            Object stableLockKey = Files.readAttributes(stableLock, BasicFileAttributes.class).fileKey();

            nextOwner = startLeaseProcess(nextSymlink, "next-owner", true);
            awaitAnySignal(nextOwner, nextOwner.waiting(), nextOwner.work(), nextOwner.error());
            assertThat(nextOwner.error()).doesNotExist();
            assertThat(nextOwner.waiting()).exists();
            assertThat(nextOwner.work()).doesNotExist();

            release(owner);
            awaitAnySignal(nextOwner, nextOwner.work(), nextOwner.error());
            assertThat(nextOwner.error()).doesNotExist();
            assertThat(nextOwner.work()).exists();
            assertThat(stableLock).exists();
            assertThat(Files.readAttributes(stableLock, BasicFileAttributes.class).fileKey())
                    .isEqualTo(stableLockKey);

            contender = startLeaseProcess(contenderSymlink, "handoff-contender", false);
            awaitAnySignal(contender, contender.blocked(), contender.work(), contender.error());
            assertThat(contender.error()).doesNotExist();
            assertThat(contender.blocked()).exists();
            assertThat(contender.work()).doesNotExist();
        } finally {
            release(contender);
            release(nextOwner);
            release(owner);
        }
    }

    @Test
    void nonUnixIdentityIsRefusedBeforeJdbcOpen() throws Exception {
        Path zip = tempDir.resolve("identity.zip");
        Path jdbcShadow = tempDir.resolve("zip-entry.sqlite");
        try (var fileSystem = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()),
                Map.of("create", "true"))) {
            Path database = fileSystem.getPath(jdbcShadow.toAbsolutePath().toString());
            Files.createDirectories(database.getParent());
            Files.createFile(database);
            HistoryImportRequest request = request(database);

            assertThatThrownBy(() -> {
                try (HistoryStagingStore ignored = HistoryStagingStore.openForStage(
                        database, request, Duration.ofMinutes(999))) {
                    // Unsupported storage must be rejected before SQLite is opened.
                }
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unsafe staging filesystem");
        }
        assertThat(jdbcShadow).doesNotExist();
    }

    private void assertAliasCannotOwnPendingWork(Path database, Path alias) throws Exception {
        LeaseProcess owner = startLeaseProcess(database, "owner", false);
        LeaseProcess contender = null;
        try {
            awaitAnySignal(owner, owner.work(), owner.error());
            assertThat(owner.error()).doesNotExist();
            assertThat(owner.work()).exists();

            contender = startLeaseProcess(alias, "contender", false);
            awaitAnySignal(contender, contender.blocked(), contender.work(), contender.error());
            assertThat(contender.error()).doesNotExist();
            assertThat(contender.blocked()).exists();
            assertThat(contender.work()).doesNotExist();
        } finally {
            release(contender);
            release(owner);
        }
    }

    private void initializePendingStage(Path database) {
        HistoryImportRequest request = request(database);
        try (HistoryStagingStore ignored = HistoryStagingStore.openForStage(
                database, request, Duration.ofMinutes(999))) {
            // Closing without processing preserves one pending interval for the subprocess owner.
        }
    }

    private LeaseProcess startLeaseProcess(Path database, String name, boolean retry) throws Exception {
        Path signalPrefix = tempDir.resolve("signals").resolve(name);
        Path runtimeDirectory = tempDir.resolve("runtime").resolve(name);
        Files.createDirectories(signalPrefix.getParent());
        Files.createDirectories(runtimeDirectory);
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + runtimeDirectory,
                "-cp", System.getProperty("surefire.test.class.path"),
                LeaseProcessMain.class.getName(), database.toAbsolutePath().toString(),
                signalPrefix.toString(), Boolean.toString(retry))
                .redirectErrorStream(true)
                .start();
        return new LeaseProcess(process, signalPrefix);
    }

    private Path onlyLeaseFile() throws Exception {
        Path lease = request().stagingDatabase().resolveSibling("staging.sqlite.stage.lock");
        assertThat(lease).exists();
        return lease;
    }

    private static void awaitAnySignal(LeaseProcess process, Path... signals) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (Path signal : signals) {
                if (Files.exists(signal)) {
                    return;
                }
            }
            if (!process.process().isAlive()) {
                break;
            }
            Thread.sleep(10);
        }
        String output = new String(process.process().getInputStream().readAllBytes());
        String error = Files.exists(process.error()) ? Files.readString(process.error()) : "";
        throw new AssertionError("Lease subprocess produced no expected signal. Output: " + output + error);
    }

    private static void release(LeaseProcess process) throws Exception {
        if (process == null) {
            return;
        }
        Files.writeString(process.release(), "release");
        if (!process.process().waitFor(5, TimeUnit.SECONDS)) {
            process.process().destroyForcibly();
            assertThat(process.process().waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private HistoryImportRequest request() {
        return request(tempDir.resolve("staging.sqlite"));
    }

    private static HistoryImportRequest request(Path database) {
        return new HistoryImportRequest("US500", "MINUTE", FROM, TO, database, "capital");
    }

    private static ImportedPage page(ImportedPrice... prices) {
        return page("page-hash", prices);
    }

    private static ImportedPage page(String payloadHash, ImportedPrice... prices) {
        return new ImportedPage(FROM, TO, List.of(prices), payloadHash);
    }

    private static ImportedPrice price(String timestamp, String closeBid, String closeAsk) {
        return new ImportedPrice(
                Instant.parse(timestamp),
                new BigDecimal("4800.1"), new BigDecimal("4800.3"),
                new BigDecimal("4801.1"), new BigDecimal("4801.3"),
                new BigDecimal("4799.1"), new BigDecimal("4799.3"),
                new BigDecimal(closeBid), new BigDecimal(closeAsk), 123L);
    }

    private static ImportedPrice priceWithCrossedHigh(String timestamp) {
        return new ImportedPrice(
                Instant.parse(timestamp),
                new BigDecimal("4800.1"), new BigDecimal("4800.3"),
                new BigDecimal("4801.3"), new BigDecimal("4801.1"),
                new BigDecimal("4799.1"), new BigDecimal("4799.3"),
                new BigDecimal("4800.1"), new BigDecimal("4800.3"), 123L);
    }

    private record LeaseProcess(Process process, Path prefix) {
        private Path waiting() {
            return Path.of(prefix + ".waiting");
        }

        private Path blocked() {
            return Path.of(prefix + ".blocked");
        }

        private Path work() {
            return Path.of(prefix + ".work");
        }

        private Path unsafe() {
            return Path.of(prefix + ".unsafe");
        }

        private Path release() {
            return Path.of(prefix + ".release");
        }

        private Path error() {
            return Path.of(prefix + ".error");
        }
    }

    public static final class LeaseProcessMain {
        private LeaseProcessMain() {
        }

        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            Path prefix = Path.of(args[1]);
            boolean retry = Boolean.parseBoolean(args[2]);
            while (true) {
                try (HistoryStagingStore store = HistoryStagingStore.openForStage(
                        database, request(database), Duration.ofMinutes(999))) {
                    if (store.nextPending() == null) {
                        throw new IllegalStateException("Lease owner found no pending provider work");
                    }
                    Files.writeString(Path.of(prefix + ".work"), "work");
                    waitForRelease(Path.of(prefix + ".release"));
                    return;
                } catch (IllegalStateException exception) {
                    if (exception.getMessage() != null && (exception.getMessage().contains("hard-linked staging database")
                            || exception.getMessage().contains("unsafe staging filesystem"))) {
                        Files.writeString(Path.of(prefix + ".unsafe"), "unsafe");
                        return;
                    }
                    if (exception.getMessage() != null && exception.getMessage().contains("already active")) {
                        if (!retry) {
                            Files.writeString(Path.of(prefix + ".blocked"), "blocked");
                            return;
                        }
                        Files.writeString(Path.of(prefix + ".waiting"), "waiting");
                        Thread.sleep(10);
                        continue;
                    }
                    Files.writeString(Path.of(prefix + ".error"), exception.toString());
                    return;
                }
            }
        }

        private static void waitForRelease(Path release) throws InterruptedException {
            while (!Files.exists(release)) {
                Thread.sleep(10);
            }
        }
    }
}
