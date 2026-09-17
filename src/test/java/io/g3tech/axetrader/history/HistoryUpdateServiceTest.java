package io.g3tech.axetrader.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryUpdateServiceTest {

    @TempDir
    Path directory;

    private Path active;
    private Path archive;
    private RecordingImportService imports;
    private HistoryUpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        active = directory.resolve("active.sqlite");
        archive = directory.resolve("active.sqlite.gz");
        Files.writeString(archive, "placeholder");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE historical_price (
                      id varchar(36) PRIMARY KEY NOT NULL, epic varchar(255), resolution varchar(255),
                      snapshot_time_utc timestamp, open_bid float NOT NULL, open_ask float NOT NULL,
                      high_bid float NOT NULL, high_ask float NOT NULL, low_bid float NOT NULL,
                      low_ask float NOT NULL, close_bid float NOT NULL, close_ask float NOT NULL,
                      last_traded_volume integer NOT NULL, source varchar(255), ingestion_time_utc timestamp)
                    """);
            statement.execute("""
                    INSERT INTO historical_price VALUES
                      ('a','US500','MINUTE','2026-08-02T22:52:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-02T22:53:00Z'),
                      ('b','GOLD','MINUTE','2026-08-05T10:00:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-05T10:01:00Z')
                    """);
        }
        imports = new RecordingImportService();
        HistoryStartProbe probe = new HistoryStartProbe((request, from, to, maxBars) ->
                new ImportedPage(from, to, from.isBefore(probeStart) ? List.of()
                        : List.of(new ImportedPrice(from, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, 1L)),
                        "probe-" + from));
        service = new HistoryUpdateService(new HistoryCursorReader(active), imports,
                new HistoryDeltaMerger(), epic -> !epic.equals("NOPE"), probe, directory.resolve(".staging"),
                () -> Instant.parse("2026-08-06T09:14:00Z"));
    }

    private Instant probeStart = Instant.parse("2024-02-05T00:00:00Z");

    @Test
    void seedsANewInstrumentFromWhereItsHistoryReallyStarts() {
        service.update("SILVER", "MINUTE", Instant.parse("2024-01-01T00:00:00Z"), active, archive);

        assertThat(imports.requests).singleElement()
                .extracting(HistoryImportRequest::from)
                .isEqualTo(Instant.parse("2024-02-05T00:00:00Z"));
    }

    @Test
    void failsASeedWhenCapitalHasNoHistoryAtAll() {
        probeStart = Instant.parse("2030-01-01T00:00:00Z");

        List<HistoryUpdateOutcome> outcomes = service.update("SILVER", "MINUTE",
                Instant.parse("2024-01-01T00:00:00Z"), active, archive);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(HistoryUpdateOutcome.Status.FAILED);
            assertThat(outcome.failure()).contains("No history");
        });
    }

    @Test
    void refusesToSeedAnEpicCapitalDoesNotKnow() {
        List<HistoryUpdateOutcome> outcomes = service.update("NOPE", "MINUTE",
                Instant.parse("2024-01-01T00:00:00Z"), active, archive);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(HistoryUpdateOutcome.Status.FAILED);
            assertThat(outcome.failure()).contains("Unknown epic");
        });
        assertThat(imports.requests).isEmpty();
    }

    @Test
    void updatesEveryStoredInstrumentFromItsOwnCursor() {
        List<HistoryUpdateOutcome> outcomes = service.update(null, null, null, active, archive);

        assertThat(outcomes).extracting(outcome -> outcome.target().epic())
                .containsExactlyInAnyOrder("US500", "GOLD");
        assertThat(imports.requests).extracting(HistoryImportRequest::from)
                .containsExactlyInAnyOrder(
                        Instant.parse("2026-08-02T21:53:00Z"),
                        Instant.parse("2026-08-05T09:01:00Z"));
        assertThat(imports.requests).allSatisfy(request ->
                assertThat(request.to()).isEqualTo(Instant.parse("2026-08-06T09:12:00Z")));
    }

    @Test
    void scopesTheRunToAnExplicitEpic() {
        service.update("US500", null, null, active, archive);

        assertThat(imports.requests).extracting(HistoryImportRequest::epic).containsExactly("US500");
    }

    @Test
    void reportsAlreadyCurrentWithoutCallingTheProvider() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO historical_price VALUES
                      ('c','US500','MINUTE','2026-08-06T09:11:00Z',1,1,1,1,1,1,1,1,10,'capital','2026-08-06T09:14:00Z')
                    """);
        }

        List<HistoryUpdateOutcome> outcomes = service.update("US500", null, null, active, archive);

        assertThat(outcomes).singleElement()
                .extracting(HistoryUpdateOutcome::status)
                .isEqualTo(HistoryUpdateOutcome.Status.ALREADY_CURRENT);
        assertThat(imports.requests).isEmpty();
    }

    @Test
    void aWeekendTopUpWithNothingNewIsAlreadyCurrentAndLeavesNoStagingFiles() throws Exception {
        imports.emptyFor = "US500";

        List<HistoryUpdateOutcome> outcomes = service.update("US500", null, null, active, archive);

        assertThat(outcomes).singleElement().extracting(HistoryUpdateOutcome::status)
                .isEqualTo(HistoryUpdateOutcome.Status.ALREADY_CURRENT);
        try (var files = Files.list(directory.resolve(".staging"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void recordsAFailureWithoutAbortingTheOtherInstruments() {
        imports.failFor = "US500";

        List<HistoryUpdateOutcome> outcomes = service.update(null, null, null, active, archive);

        assertThat(outcomes).filteredOn(outcome -> outcome.target().epic().equals("US500"))
                .singleElement()
                .satisfies(outcome -> {
                    assertThat(outcome.status()).isEqualTo(HistoryUpdateOutcome.Status.FAILED);
                    assertThat(outcome.failure()).contains("provider rejected the request");
                });
        assertThat(outcomes).filteredOn(outcome -> outcome.target().epic().equals("GOLD"))
                .singleElement()
                .extracting(HistoryUpdateOutcome::status)
                .isEqualTo(HistoryUpdateOutcome.Status.MERGED);
    }

    @Test
    void failsClosedWhenSeedingAnUnknownInstrumentWithoutAStart() {
        assertThatIllegalStateException()
                .isThrownBy(() -> service.update("NASDAQ", "MINUTE", null, active, archive))
                .withMessageContaining("explicit from");
    }

    /** Stands in for the real Capital-backed stage: writes a minimal valid delta instead of paging. */
    private static final class RecordingImportService extends HistoryImportService {

        private final List<HistoryImportRequest> requests = new java.util.ArrayList<>();
        private String failFor;

        RecordingImportService() {
            super((request, from, to, maxBars) -> {
                throw new UnsupportedOperationException("not used");
            }, new HistoryDatabasePromoter());
        }

        private String emptyFor;

        @Override
        public HistoryImportAudit stage(HistoryImportRequest request, Path activeDatabase, Path archive,
                                        boolean allowEmpty) {
            requests.add(request);
            if (request.epic().equals(failFor)) {
                throw new IllegalStateException("provider rejected the request");
            }
            HistoryStagingStoreFixtures.writeMinimalDelta(request);
            if (request.epic().equals(emptyFor)) {
                try {
                    Files.writeString(request.stagingDatabase().resolveSibling(
                            request.stagingDatabase().getFileName() + ".stage.lock"), "lease");
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
                return new HistoryImportAudit(request.from(), request.to(), null, null, 0, 0, 0, 0, 0, 0,
                        java.util.Map.of(), List.of(new HistoryCoverageGap(request.from(), request.to(),
                        "EMPTY_BASE", "h")), List.of(), true);
            }
            return HistoryStagingStoreFixtures.passingAudit(request);
        }
    }
}
