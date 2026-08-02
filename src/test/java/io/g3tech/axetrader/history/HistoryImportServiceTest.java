package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryImportServiceTest {

    private static final Instant FROM = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2024-01-01T00:04:00Z");

    @TempDir
    Path tempDir;

    @Test
    void probeFetchesAndValidatesWithoutCreatingAnySQLiteFile() throws Exception {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:00:00Z"), crossedClose("2024-01-01T00:01:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        HistoryImportAudit audit = service.probe(request());

        assertThat(source.calls).containsExactly(new FetchCall(FROM, TO, 1_000));
        assertThat(audit.receivedCount()).isEqualTo(2);
        assertThat(audit.acceptedCount()).isEqualTo(1);
        assertThat(audit.rejectedCount()).isEqualTo(1);
        assertThat(audit.exclusionsByReason()).containsEntry("CLOSE_BID_ABOVE_ASK", 1L);
        try (var files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void stageLeavesActiveFilesUnchangedAndCreatesOnlyNamedStagingDatabase() throws Exception {
        Path active = tempDir.resolve("axe-trader.sqlite");
        Path archive = tempDir.resolve("axe-trader.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        byte[] activeBefore = Files.readAllBytes(active);
        byte[] archiveBefore = Files.readAllBytes(archive);
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        HistoryImportAudit audit = service.stage(request());

        assertThat(audit.acceptedMinuteCount()).isEqualTo(1);
        assertThat(Files.readAllBytes(active)).isEqualTo(activeBefore);
        assertThat(Files.readAllBytes(archive)).isEqualTo(archiveBefore);
        assertThat(request().stagingDatabase()).exists();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("axe-trader.sqlite", "axe-trader.sqlite.gz", "stage.sqlite");
        }
    }

    @Test
    void stageAdvancesToOneMinuteAfterGreatestReturnedTimestamp() {
        RecordingSource source = new RecordingSource(List.of(
                page(FROM, TO, price("2024-01-01T00:01:00Z"), price("2024-01-01T00:00:00Z")),
                page(Instant.parse("2024-01-01T00:02:00Z"), TO,
                        price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        service.stage(request());

        assertThat(source.calls).containsExactly(
                new FetchCall(FROM, TO, 1_000),
                new FetchCall(Instant.parse("2024-01-01T00:02:00Z"), TO, 1_000));
    }

    @Test
    void stageFailsClosedOnAnEmptyPage() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO)));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void stageRefusesToReuseAnExistingNamedDatabase() throws Exception {
        Files.writeString(request().stagingDatabase(), "existing-stage");
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing");

        assertThat(Files.readString(request().stagingDatabase())).isEqualTo("existing-stage");
        assertThat(source.calls).isEmpty();
    }

    @Test
    void promoteReauditsStagingBeforeChangingActiveFiles() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z")))), new HistoryDatabasePromoter()).stage(request);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase())) {
            connection.createStatement().executeUpdate("UPDATE historical_price SET close_bid = close_ask + 1");
        }
        Path active = tempDir.resolve("active.sqlite");
        Path archive = tempDir.resolve("active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        HistoryImportService service = new HistoryImportService(
                (ignoredRequest, ignoredFrom, ignoredTo, ignoredMaxBars) -> {
                    throw new AssertionError("promotion must not fetch provider data");
                },
                new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.promote(request, active, archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit");
        assertThat(Files.readString(active)).isEqualTo("legacy-active");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(request.stagingDatabase()).exists();
    }

    @Test
    void promoteDiscoversTheStoredRequestWithoutCommandLineRangeProperties() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z")))), new HistoryDatabasePromoter()).stage(request);
        Path active = tempDir.resolve("active.sqlite");
        Path archive = tempDir.resolve("active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        HistoryImportService service = new HistoryImportService(
                (ignoredRequest, ignoredFrom, ignoredTo, ignoredMaxBars) -> {
                    throw new AssertionError("promotion must not fetch provider data");
                },
                new HistoryDatabasePromoter());

        service.promote(request.stagingDatabase(), active, archive);

        assertThat(request.stagingDatabase()).doesNotExist();
        assertThat(active).exists();
        assertThat(archive).exists();
    }

    @Test
    void promoteRefusesAPartialStageLeftByAPagingFailure() throws Exception {
        RecordingSource source = new RecordingSource(List.of(
                page(FROM, TO, price("2024-01-01T00:01:00Z")),
                page(Instant.parse("2024-01-01T00:02:00Z"), TO)));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());
        assertThatThrownBy(() -> service.stage(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
        Path active = tempDir.resolve("active.sqlite");
        Path archive = tempDir.resolve("active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");

        assertThatThrownBy(() -> service.promote(request().stagingDatabase(), active, archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed");

        assertThat(Files.readString(active)).isEqualTo("legacy-active");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(request().stagingDatabase()).exists();
    }

    @Test
    void runnerPromotesUsingOnlyStagingAndDefaultedActivePaths() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z")))), new HistoryDatabasePromoter()).stage(request);
        Path active = tempDir.resolve("active.sqlite");
        Path archive = tempDir.resolve("active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        HistoryImportProperties properties = new HistoryImportProperties(
                true, "promote", null, null, null, null, request.stagingDatabase(), active, archive);
        HistoryImportService service = new HistoryImportService(
                (ignoredRequest, ignoredFrom, ignoredTo, ignoredMaxBars) -> {
                    throw new AssertionError("promotion must not fetch provider data");
                },
                new HistoryDatabasePromoter());

        new HistoryImportRunner(properties, service).run(null);

        assertThat(request.stagingDatabase()).doesNotExist();
        assertThat(Files.readString(active, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("SQLite format 3");
    }

    @Test
    void enabledImportStartupDoesNotCreateTheNormalApplicationDatabase() throws Exception {
        Files.createDirectory(tempDir.resolve("data"));
        String classPath = System.getProperty("surefire.test.class.path");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath,
                ImportApplicationProcess.class.getName(),
                "--axe-trader.history-import.enabled=true",
                "--axe-trader.history-import.mode=unknown",
                "--axe-trader.history-import.epic=US500",
                "--axe-trader.history-import.resolution=MINUTE",
                "--axe-trader.history-import.from=2024-01-01T00:00:00Z",
                "--axe-trader.history-import.to=2024-01-01T00:04:00Z",
                "--axe-trader.history-import.staging-database=stage.sqlite")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(output).contains("Unknown history import mode");
        assertThat(tempDir.resolve("data/axe-trader.sqlite")).doesNotExist();
        assertThat(tempDir.resolve("data/axe-trader.sqlite.gz")).doesNotExist();
    }

    private HistoryImportRequest request() {
        return new HistoryImportRequest("US500", "MINUTE", FROM, TO,
                tempDir.resolve("stage.sqlite"), "capital");
    }

    private static ImportedPage page(Instant requestedFrom, Instant requestedTo, ImportedPrice... prices) {
        return new ImportedPage(requestedFrom, requestedTo, List.of(prices),
                "hash-" + requestedFrom + '-' + prices.length);
    }

    private static ImportedPrice price(String timestamp) {
        return price(timestamp, "4800.1", "4800.3");
    }

    private static ImportedPrice crossedClose(String timestamp) {
        return price(timestamp, "4800.3", "4800.1");
    }

    private static ImportedPrice price(String timestamp, String closeBid, String closeAsk) {
        return new ImportedPrice(
                Instant.parse(timestamp),
                new BigDecimal("4800.1"), new BigDecimal("4800.3"),
                new BigDecimal("4801.1"), new BigDecimal("4801.3"),
                new BigDecimal("4799.1"), new BigDecimal("4799.3"),
                new BigDecimal(closeBid), new BigDecimal(closeAsk), 123L);
    }

    private record FetchCall(Instant from, Instant to, int maxBars) {
    }

    private static final class RecordingSource implements HistoricalPricePageSource {
        private final List<ImportedPage> pages;
        private final List<FetchCall> calls = new ArrayList<>();
        private int nextPage;

        private RecordingSource(List<ImportedPage> pages) {
            this.pages = pages;
        }

        @Override
        public ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive,
                                  int maxBars) {
            calls.add(new FetchCall(fromInclusive, toExclusive, maxBars));
            return pages.get(nextPage++);
        }
    }

    public static final class ImportApplicationProcess {
        public static void main(String[] args) throws Exception {
            Class<?> application = Class.forName("io.g3tech.axetrader.AxeTraderApplication");
            var main = application.getDeclaredMethod("main", String[].class);
            main.setAccessible(true);
            main.invoke(null, (Object) args);
        }
    }
}
