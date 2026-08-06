package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        HistoryImportAudit audit = service.probe(request(), activeDatabase(), archive());

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
    void stageLeavesActiveFilesUnchangedAndCreatesOnlyNamedStagingArtifacts() throws Exception {
        Path active = tempDir.resolve("axe-trader.sqlite");
        Path archive = tempDir.resolve("axe-trader.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        byte[] activeBefore = Files.readAllBytes(active);
        byte[] archiveBefore = Files.readAllBytes(archive);
        RecordingSource source = new RecordingSource(List.of(completePage(FROM, TO)));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        HistoryImportAudit audit = service.stage(request(), active, archive);

        assertThat(audit.acceptedMinuteCount()).isEqualTo(4);
        assertThat(Files.readAllBytes(active)).isEqualTo(activeBefore);
        assertThat(Files.readAllBytes(archive)).isEqualTo(archiveBefore);
        assertThat(request().stagingDatabase()).exists();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder(
                            "axe-trader.sqlite", "axe-trader.sqlite.gz", "stage.sqlite",
                            "stage.sqlite.stage.lock");
        }
    }

    @Test
    void stageUsesTheFullBoundedWindowInsteadOfTheGreatestReturnedTimestamp() {
        RecordingSource source = new RecordingSource(List.of(completePage(FROM, TO)));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        service.stage(request(), activeDatabase(), archive());

        assertThat(source.calls).containsExactly(new FetchCall(FROM, TO, 1_000));
    }

    @Test
    void stageRecordsAnEmptyBoundedWindowAsARecognizedClosure() {
        Instant firstWindowEnd = FROM.plusSeconds(999 * 60L);
        Instant longImportEnd = FROM.plusSeconds(1_000 * 60L);
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, longImportEnd,
                tempDir.resolve("closure-stage.sqlite"), "capital");
        RecordingSource source = new RecordingSource(List.of(
                completePage(FROM, firstWindowEnd),
                page(firstWindowEnd, longImportEnd)));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        HistoryImportAudit audit = service.stage(request, activeDatabase(), archive());

        assertThat(audit.recognizedSessionClosures()).isNotEmpty();
        assertThat(audit.continuityGaps()).isEmpty();
        assertThat(audit.isConsistent()).isTrue();
    }

    @Test
    void stageDiscardsTheProvidersNominalInclusiveUpperBound() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:00:00Z"), price("2024-01-01T00:01:00Z"),
                price("2024-01-01T00:02:00Z"), price("2024-01-01T00:03:00Z"),
                price("2024-01-01T00:04:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        HistoryImportAudit audit = service.stage(request(), activeDatabase(), archive());

        assertThat(audit.receivedCount()).isEqualTo(4);
        assertThat(audit.acceptedCount()).isEqualTo(4);
        assertThat(audit.observationCount()).isEqualTo(1);
        assertThat(audit.rawReceivedCount()).isEqualTo(4);
        assertThat(audit.pendingWorkCount()).isZero();
        assertThat(audit.rejectedCount()).isZero();
        assertThat(audit.exclusionsByReason()).doesNotContainKey("TIMESTAMP_OUT_OF_RANGE");
    }

    @Test
    void stageRejectsOffMinutePricesWithoutConsumingThePendingInterval() throws Exception {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:00:30Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole UTC minute");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request().stagingDatabase());
             var rows = connection.createStatement().executeQuery("""
                     SELECT requested_from_utc, requested_to_utc, state
                     FROM history_import_work
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(FROM.toString());
            assertThat(rows.getString(2)).isEqualTo(TO.toString());
            assertThat(rows.getString(3)).isEqualTo("PENDING");
            assertThat(rows.next()).isFalse();
        }
        assertThat(completionRows(request().stagingDatabase())).isZero();
    }

    @Test
    void stageSplitsLongImportsIntoCapitalSupportedBoundedWindows() {
        Instant firstWindowEnd = FROM.plusSeconds(999 * 60L);
        Instant longImportEnd = FROM.plusSeconds(1_000 * 60L);
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, longImportEnd,
                tempDir.resolve("long-stage.sqlite"), "capital");
        RecordingSource source = new RecordingSource(List.of(
                completePage(FROM, firstWindowEnd),
                completePage(firstWindowEnd, longImportEnd)));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        service.stage(request, activeDatabase(), archive());

        assertThat(source.calls).containsExactly(
                new FetchCall(FROM, firstWindowEnd, 1_000),
                new FetchCall(firstWindowEnd, longImportEnd, 1_000));
    }

    @Test
    void stageRetainsSparseParentAndFetchesOnlyItsMaximalMissingRuns() {
        Instant to = FROM.plusSeconds(6 * 60L);
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, to,
                tempDir.resolve("sparse-stage.sqlite"), "capital");
        ScriptedSource source = new ScriptedSource(Map.of(
                new Interval(FROM, to), page(FROM, to,
                        price("2024-01-01T00:00:00Z"), price("2024-01-01T00:02:00Z"),
                        price("2024-01-01T00:03:00Z"), price("2024-01-01T00:05:00Z")),
                new Interval(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                page(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                new Interval(FROM.plusSeconds(240), FROM.plusSeconds(300)),
                page(FROM.plusSeconds(240), FROM.plusSeconds(300))));

        HistoryImportAudit audit = new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());

        assertThat(source.calls).containsExactly(
                new FetchCall(FROM, to, 1_000),
                new FetchCall(FROM.plusSeconds(60), FROM.plusSeconds(120), 1_000),
                new FetchCall(FROM.plusSeconds(240), FROM.plusSeconds(300), 1_000));
        assertThat(audit.receivedCount()).isEqualTo(4);
        assertThat(audit.acceptedCount()).isEqualTo(4);
        assertThat(audit.observationCount()).isEqualTo(3);
        assertThat(audit.rawReceivedCount()).isEqualTo(4);
        assertThat(audit.pendingWorkCount()).isZero();
        assertThat(audit.duplicateCount()).isZero();
        assertThat(audit.recognizedClosureMinuteCount()).isEqualTo(2);
        assertThat(audit.recognizedSessionClosures())
                .allSatisfy(closure -> assertThat(closure.provenance()).isEqualTo("CAPITAL_EMPTY_OR_404"));
        assertThat(audit.isConsistent()).isTrue();
    }

    @Test
    void gapResponsesConvergeByQueueingOnlyTheirSmallerMissingRuns() {
        Instant to = FROM.plusSeconds(5 * 60L);
        Instant gapFrom = FROM.plusSeconds(60);
        Instant gapTo = FROM.plusSeconds(240);
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, to,
                tempDir.resolve("converging-stage.sqlite"), "capital");
        ScriptedSource source = new ScriptedSource(Map.of(
                new Interval(FROM, to), page(FROM, to,
                        price("2024-01-01T00:00:00Z"), price("2024-01-01T00:04:00Z")),
                new Interval(gapFrom, gapTo), page(gapFrom, gapTo, price("2024-01-01T00:02:00Z")),
                new Interval(gapFrom, FROM.plusSeconds(120)), page(gapFrom, FROM.plusSeconds(120)),
                new Interval(FROM.plusSeconds(180), gapTo), page(FROM.plusSeconds(180), gapTo)));

        HistoryImportAudit audit = new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());

        assertThat(source.calls).containsExactly(
                new FetchCall(FROM, to, 1_000),
                new FetchCall(gapFrom, gapTo, 1_000),
                new FetchCall(gapFrom, FROM.plusSeconds(120), 1_000),
                new FetchCall(FROM.plusSeconds(180), gapTo, 1_000));
        assertThat(audit.receivedCount()).isEqualTo(3);
        assertThat(audit.acceptedMinuteCount()).isEqualTo(3);
        assertThat(audit.recognizedClosureMinuteCount()).isEqualTo(2);
        assertThat(audit.isConsistent()).isTrue();
    }

    @Test
    void resumeFetchesOnlyDurablyPendingIntervalsAndMatchesUninterruptedAudit() throws Exception {
        Instant to = FROM.plusSeconds(4 * 60L);
        HistoryImportRequest interruptedRequest = new HistoryImportRequest("US500", "MINUTE", FROM, to,
                tempDir.resolve("interrupted-stage.sqlite"), "capital");
        Files.writeString(activeDatabase(), "legacy-active");
        Files.writeString(archive(), "legacy-archive");
        byte[] activeBefore = Files.readAllBytes(activeDatabase());
        byte[] archiveBefore = Files.readAllBytes(archive());
        ImportedPage sparse = page(FROM, to,
                price("2024-01-01T00:00:00Z"), price("2024-01-01T00:02:00Z"));
        HistoricalPricePageSource interrupted = new HistoricalPricePageSource() {
            int calls;

            @Override
            public ImportedPage fetch(HistoryImportRequest ignored, Instant from, Instant until, int maxBars) {
                if (calls++ == 0) {
                    return sparse;
                }
                throw new IllegalStateException("simulated interruption");
            }
        };

        assertThatThrownBy(() -> new HistoryImportService(interrupted, new HistoryDatabasePromoter())
                .stage(interruptedRequest, activeDatabase(), archive()))
                .hasMessageContaining("simulated interruption");
        assertThat(completionRows(interruptedRequest.stagingDatabase())).isZero();
        assertThat(Files.readAllBytes(activeDatabase())).isEqualTo(activeBefore);
        assertThat(Files.readAllBytes(archive())).isEqualTo(archiveBefore);
        try (HistoryStagingStore store = HistoryStagingStore.open(interruptedRequest.stagingDatabase())) {
            HistoryImportAudit partialAudit = store.audit(interruptedRequest);
            assertThat(partialAudit.observationCount()).isEqualTo(1);
            assertThat(partialAudit.pendingWorkCount()).isEqualTo(2);
            assertThat(partialAudit.isConsistent()).isFalse();
        }

        ScriptedSource resumed = new ScriptedSource(Map.of(
                new Interval(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                page(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                new Interval(FROM.plusSeconds(180), to), page(FROM.plusSeconds(180), to)));
        HistoryImportAudit resumedAudit = new HistoryImportService(resumed, new HistoryDatabasePromoter())
                .stage(interruptedRequest, activeDatabase(), archive());

        HistoryImportRequest uninterruptedRequest = new HistoryImportRequest("US500", "MINUTE", FROM, to,
                tempDir.resolve("uninterrupted-stage.sqlite"), "capital");
        ScriptedSource uninterrupted = new ScriptedSource(Map.of(
                new Interval(FROM, to), sparse,
                new Interval(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                page(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                new Interval(FROM.plusSeconds(180), to), page(FROM.plusSeconds(180), to)));
        HistoryImportAudit uninterruptedAudit = new HistoryImportService(uninterrupted, new HistoryDatabasePromoter())
                .stage(uninterruptedRequest, activeDatabase(), archive());

        assertThat(resumed.calls).containsExactly(
                new FetchCall(FROM.plusSeconds(60), FROM.plusSeconds(120), 1_000),
                new FetchCall(FROM.plusSeconds(180), to, 1_000));
        assertThat(resumedAudit).isEqualTo(uninterruptedAudit);
        assertThat(resumedAudit.duplicateCount()).isZero();
        assertThat(completionRows(interruptedRequest.stagingDatabase())).isEqualTo(1);
        assertThat(completionFingerprint(interruptedRequest.stagingDatabase()))
                .isEqualTo(completionFingerprint(uninterruptedRequest.stagingDatabase()));
    }

    @Test
    void resumeRefusesMismatchedIdentityAndUnknownAlgorithmVersionBeforeFetching() throws Exception {
        HistoricalPricePageSource interrupted = (ignored, from, to, maxBars) -> {
            throw new IllegalStateException("simulated interruption");
        };
        HistoryImportRequest original = request();
        assertThatThrownBy(() -> new HistoryImportService(interrupted, new HistoryDatabasePromoter())
                .stage(original, activeDatabase(), archive()))
                .hasMessageContaining("simulated interruption");

        List<HistoryImportRequest> mismatches = List.of(
                new HistoryImportRequest("DE40", "MINUTE", FROM, TO, original.stagingDatabase(), "capital"),
                new HistoryImportRequest("US500", "MINUTE", FROM, TO, original.stagingDatabase(), "other"),
                new HistoryImportRequest("US500", "MINUTE", FROM.plusSeconds(60), TO,
                        original.stagingDatabase(), "capital"),
                new HistoryImportRequest("US500", "MINUTE", FROM, TO.plusSeconds(60),
                        original.stagingDatabase(), "capital"));
        for (HistoryImportRequest mismatch : mismatches) {
            RecordingSource mismatchSource = new RecordingSource(List.of());
            assertThatThrownBy(() -> new HistoryImportService(mismatchSource, new HistoryDatabasePromoter())
                    .stage(mismatch, activeDatabase(), archive()))
                    .hasMessageContaining("identity");
            assertThat(mismatchSource.calls).isEmpty();
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + original.stagingDatabase())) {
            connection.createStatement().executeUpdate("UPDATE history_import_run SET algorithm_version = 999");
        }
        RecordingSource versionSource = new RecordingSource(List.of());
        assertThatThrownBy(() -> new HistoryImportService(versionSource, new HistoryDatabasePromoter())
                .stage(original, activeDatabase(), archive()))
                .hasMessageContaining("version");
        assertThat(versionSource.calls).isEmpty();
    }

    @Test
    void resumeRefusesForgedRunIdAndForeignWorkBeforeProviderAccess() throws Exception {
        HistoricalPricePageSource interrupted = (ignored, from, to, maxBars) -> {
            throw new IllegalStateException("simulated interruption");
        };
        HistoryImportRequest request = request();
        assertThatThrownBy(() -> new HistoryImportService(interrupted, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive())).hasMessageContaining("simulated interruption");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase())) {
            connection.createStatement().executeUpdate("UPDATE history_import_run SET import_run_id='forged'");
        }
        HistoricalPricePageSource mustNotFetch = (ignored, from, to, maxBars) -> {
            throw new AssertionError("provider must not be called");
        };
        assertThatThrownBy(() -> new HistoryImportService(mustNotFetch, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run identity");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase())) {
            connection.createStatement().executeUpdate("UPDATE history_import_run SET import_run_id=(SELECT import_run_id FROM history_import_work LIMIT 1)");
            connection.createStatement().executeUpdate("""
                    INSERT INTO history_import_work(import_run_id, requested_from_utc, requested_to_utc, state)
                    VALUES ('foreign-run','2024-01-01T00:00:00Z','2024-01-01T00:01:00Z','PENDING')
                    """);
        }
        assertThatThrownBy(() -> new HistoryImportService(mustNotFetch, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("foreign run");
    }

    @Test
    void completedStageCannotBeReusedEvenWithTheSameIdentity() {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(completePage(FROM, TO))), new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());
        RecordingSource source = new RecordingSource(List.of());

        assertThatThrownBy(() -> new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive()))
                .hasMessageContaining("completed");
        assertThat(source.calls).isEmpty();
    }

    @Test
    void stageNeverUsesTheRetainedTaskFiveDatabasePath() {
        Path retained = tempDir.resolve("data/us500-clean-stage.sqlite");
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, TO,
                retained, "capital");
        RecordingSource source = new RecordingSource(List.of(completePage(FROM, TO)));

        assertThatThrownBy(() -> new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive()))
                .hasMessageContaining("Task 5");
        assertThat(source.calls).isEmpty();
        assertThat(retained).doesNotExist();
    }

    @Test
    void stageRefusesToCertifyAnUnexplainedMissingMinute() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:00:00Z"), price("2024-01-01T00:02:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no new coverage progress");

        try (HistoryStagingStore store = HistoryStagingStore.open(request().stagingDatabase())) {
            HistoryImportAudit audit = store.audit(request());
            assertThat(audit.continuityGaps()).anySatisfy(gap -> {
                assertThat(gap.fromInclusive()).isEqualTo(Instant.parse("2024-01-01T00:01:00Z"));
                assertThat(gap.toExclusive()).isEqualTo(Instant.parse("2024-01-01T00:02:00Z"));
            });
            assertThat(audit.isConsistent()).isFalse();
        }
    }

    @Test
    void stageRefusesASingleLeadingEdgeCandleWithAnUncoveredSuffix() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO, price("2024-01-01T00:00:00Z"))));

        assertThatThrownBy(() -> new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no new coverage progress");
    }

    @Test
    void stageRefusesASingleTrailingEdgeCandleWithAnUncoveredPrefix() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO, price("2024-01-01T00:03:00Z"))));

        assertThatThrownBy(() -> new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no new coverage progress");
    }

    @Test
    void stageRefusesMalformedExistingNamedDatabase() throws Exception {
        Files.writeString(request().stagingDatabase(), "existing-stage");
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid import-run metadata");

        assertThat(Files.readString(request().stagingDatabase())).isEqualTo("existing-stage");
        assertThat(source.calls).isEmpty();
    }

    @Test
    void stageRejectsAbsentActivePathUsedAsTheStagingPathBeforeFetching() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), request().stagingDatabase(), archive()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");

        assertThat(request().stagingDatabase()).doesNotExist();
        assertThat(source.calls).isEmpty();
    }

    @Test
    void stageRejectsAbsentArchivePathUsedAsTheStagingPathBeforeFetching() {
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), request().stagingDatabase()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");

        assertThat(request().stagingDatabase()).doesNotExist();
        assertThat(source.calls).isEmpty();
    }

    @Test
    void stageRejectsAnExistingFilesystemAliasOfTheActiveDatabaseBeforeFetching() throws Exception {
        Files.writeString(activeDatabase(), "legacy-active");
        Files.createSymbolicLink(request().stagingDatabase(), activeDatabase());
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");

        assertThat(Files.readString(activeDatabase())).isEqualTo("legacy-active");
        assertThat(source.calls).isEmpty();
    }

    @Test
    void stageRejectsADanglingSymlinkAliasOfAnAbsentActiveDatabaseBeforeFetching() throws Exception {
        Files.createSymbolicLink(request().stagingDatabase(), activeDatabase());
        RecordingSource source = new RecordingSource(List.of(page(FROM, TO,
                price("2024-01-01T00:03:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.stage(request(), activeDatabase(), archive()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");

        assertThat(activeDatabase()).doesNotExist();
        assertThat(source.calls).isEmpty();
    }

    @Test
    void promoteReauditsStagingBeforeChangingActiveFiles() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(completePage(FROM, TO))), new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());
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
    void promoteRejectsTamperedClosurePayloadHashBeforeChangingActiveFiles() throws Exception {
        assertPromotionRejectsClosureTamper(
                "UPDATE history_import_closure SET payload_hash='forged'",
                "matching exact empty provider observation");
    }

    @Test
    void promoteRejectsTamperedClosureProvenanceBeforeChangingActiveFiles() throws Exception {
        assertPromotionRejectsClosureTamper(
                "UPDATE history_import_closure SET provenance='SYNTHESIZED'",
                "closure provenance");
    }

    @Test
    void diagnosticRawPageCountersDoNotInvalidateCompletedEvidence() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(completePage(FROM, TO))), new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase())) {
            connection.createStatement().executeUpdate("""
                    UPDATE history_import_page
                    SET received_count=received_count+10, accepted_count=accepted_count+10
                    """);
        }
        Path active = tempDir.resolve("raw-active.sqlite");
        Path archive = tempDir.resolve("raw-active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");

        new HistoryImportService(
                (ignoredRequest, ignoredFrom, ignoredTo, ignoredMaxBars) -> {
                    throw new AssertionError("promotion must not fetch provider data");
                },
                new HistoryDatabasePromoter()).promote(request, active, archive);

        assertThat(request.stagingDatabase()).doesNotExist();
        assertThat(active).exists();
        assertThat(archive).exists();
    }

    @Test
    void promoteDiscoversTheStoredRequestWithoutCommandLineRangeProperties() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(completePage(FROM, TO))), new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());
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
        Instant firstWindowEnd = FROM.plusSeconds(999 * 60L);
        Instant longImportEnd = FROM.plusSeconds(1_000 * 60L);
        HistoryImportRequest request = new HistoryImportRequest("US500", "MINUTE", FROM, longImportEnd,
                tempDir.resolve("partial-stage.sqlite"), "capital");
        RecordingSource source = new RecordingSource(List.of(
                page(FROM, firstWindowEnd, price("2024-01-01T00:01:00Z")),
                page(FROM, longImportEnd, price("2024-01-01T16:39:00Z"))));
        HistoryImportService service = new HistoryImportService(source, new HistoryDatabasePromoter());
        assertThatThrownBy(() -> service.stage(request, activeDatabase(), archive()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected page bounds");
        Path active = tempDir.resolve("active.sqlite");
        Path archive = tempDir.resolve("active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");

        assertThatThrownBy(() -> service.promote(request.stagingDatabase(), active, archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed");

        assertThat(Files.readString(active)).isEqualTo("legacy-active");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(request.stagingDatabase()).exists();
    }

    @Test
    void runnerPromotesUsingOnlyStagingAndDefaultedActivePaths() throws Exception {
        HistoryImportRequest request = request();
        new HistoryImportService(new RecordingSource(List.of(completePage(FROM, TO))), new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());
        Path active = tempDir.resolve("active.sqlite");
        Path archive = tempDir.resolve("active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        HistoryImportProperties properties = new HistoryImportProperties(
                true, "promote", null, null, null, null, request.stagingDatabase(), null, active, archive);
        HistoryImportService service = new HistoryImportService(
                (ignoredRequest, ignoredFrom, ignoredTo, ignoredMaxBars) -> {
                    throw new AssertionError("promotion must not fetch provider data");
                },
                new HistoryDatabasePromoter());

        HistoryCursorReader cursorReader = new HistoryCursorReader(active);
        HistoryUpdateService updateService = new HistoryUpdateService(cursorReader, service,
                new HistoryDeltaMerger(), tempDir.resolve(".staging"), java.time.Instant::now);

        new HistoryImportRunner(properties, service, updateService,
                new HistoryDirtyDataReporter(), cursorReader, new HistoryArchiveWriter()).run(null);

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

    @Test
    void externalConfigurationCanEnableAFileFreeProbe() throws Exception {
        Files.createDirectory(tempDir.resolve("data"));
        Path configuration = tempDir.resolve("history-import.properties");
        Files.writeString(configuration, """
                axe-trader.history-import.enabled=true
                axe-trader.history-import.mode=probe
                axe-trader.history-import.epic=US500
                axe-trader.history-import.resolution=MINUTE
                axe-trader.history-import.from=2024-01-01T00:00:00Z
                axe-trader.history-import.to=2024-01-01T00:04:00Z
                axe-trader.history-import.staging-database=stage.sqlite
                spring.main.web-application-type=none
                """);

        ProcessResult result = runImportApplication(List.of(
                "--spring.config.additional-location=" + configuration.toUri(),
                "--spring.main.sources=" + ImportTestConfiguration.class.getName()), Map.of());

        assertThat(result.output()).contains("Probe audit:");
        assertThat(tempDir.resolve("stage.sqlite")).doesNotExist();
        assertThat(tempDir.resolve("data/axe-trader.sqlite")).doesNotExist();
        assertThat(tempDir.resolve("data/axe-trader.sqlite.gz")).doesNotExist();
    }

    @Test
    void springApplicationJsonCanEnableAStageWithoutCreatingActiveFiles() throws Exception {
        Files.createDirectory(tempDir.resolve("data"));
        String json = """
                {"axe-trader":{"history-import":{
                  "enabled":true,"mode":"stage","epic":"US500","resolution":"MINUTE",
                  "from":"2024-01-01T00:00:00Z","to":"2024-01-01T00:04:00Z",
                  "staging-database":"stage.sqlite"}},
                  "spring":{"main":{"web-application-type":"none"}}}
                """.replace("\n", "");

        ProcessResult result = runImportApplication(List.of(
                "--spring.main.sources=" + ImportTestConfiguration.class.getName()),
                Map.of("SPRING_APPLICATION_JSON", json));

        assertThat(result.output()).contains("Staged import audit:");
        assertThat(tempDir.resolve("stage.sqlite")).exists();
        assertThat(tempDir.resolve("data/axe-trader.sqlite")).doesNotExist();
        assertThat(tempDir.resolve("data/axe-trader.sqlite.gz")).doesNotExist();
    }

    private HistoryImportRequest request() {
        return new HistoryImportRequest("US500", "MINUTE", FROM, TO,
                tempDir.resolve("stage.sqlite"), "capital");
    }

    private void assertPromotionRejectsClosureTamper(String tamperSql, String expectedMessage) throws Exception {
        HistoryImportRequest request = request();
        ScriptedSource source = new ScriptedSource(Map.of(
                new Interval(FROM, TO), page(FROM, TO,
                        price("2024-01-01T00:00:00Z"), price("2024-01-01T00:02:00Z"),
                        price("2024-01-01T00:03:00Z")),
                new Interval(FROM.plusSeconds(60), FROM.plusSeconds(120)),
                page(FROM.plusSeconds(60), FROM.plusSeconds(120))));
        new HistoryImportService(source, new HistoryDatabasePromoter())
                .stage(request, activeDatabase(), archive());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase())) {
            connection.createStatement().executeUpdate(tamperSql);
        }
        Path active = tempDir.resolve("closure-active.sqlite");
        Path archive = tempDir.resolve("closure-active.sqlite.gz");
        Files.writeString(active, "legacy-active");
        Files.writeString(archive, "legacy-archive");
        HistoryImportService service = new HistoryImportService(
                (ignoredRequest, ignoredFrom, ignoredTo, ignoredMaxBars) -> {
                    throw new AssertionError("promotion must not fetch provider data");
                },
                new HistoryDatabasePromoter());

        assertThatThrownBy(() -> service.promote(request, active, archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
        assertThat(Files.readString(active)).isEqualTo("legacy-active");
        assertThat(Files.readString(archive)).isEqualTo("legacy-archive");
        assertThat(request.stagingDatabase()).exists();
    }

    private Path activeDatabase() {
        return tempDir.resolve("active.sqlite");
    }

    private Path archive() {
        return tempDir.resolve("active.sqlite.gz");
    }

    private ProcessResult runImportApplication(List<String> arguments, Map<String, String> environment)
            throws Exception {
        String classPath = System.getProperty("surefire.test.class.path");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classPath);
        command.add(ImportApplicationProcess.class.getName());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new ProcessResult(process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    private static ImportedPage page(Instant requestedFrom, Instant requestedTo, ImportedPrice... prices) {
        return new ImportedPage(requestedFrom, requestedTo, List.of(prices),
                "hash-" + requestedFrom + '-' + prices.length);
    }

    private static ImportedPage completePage(Instant requestedFrom, Instant requestedTo) {
        List<ImportedPrice> prices = new ArrayList<>();
        for (Instant timestamp = requestedFrom; timestamp.isBefore(requestedTo); timestamp = timestamp.plusSeconds(60)) {
            prices.add(price(timestamp.toString()));
        }
        return new ImportedPage(requestedFrom, requestedTo, prices, "complete-" + requestedFrom);
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
            if (nextPage == pages.size()) {
                return new ImportedPage(fromInclusive, toExclusive, List.of(missingTimestampPrice()), "missing-page");
            }
            return pages.get(nextPage++);
        }
    }

    private static ImportedPrice missingTimestampPrice() {
        return new ImportedPrice(null,
                new BigDecimal("4800.1"), new BigDecimal("4800.3"),
                new BigDecimal("4801.1"), new BigDecimal("4801.3"),
                new BigDecimal("4799.1"), new BigDecimal("4799.3"),
                new BigDecimal("4800.1"), new BigDecimal("4800.3"), 123L);
    }

    private static long completionRows(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var rows = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='history_import_completion'")) {
            return rows.getLong(1);
        }
    }

    private static String completionFingerprint(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var rows = connection.createStatement().executeQuery(
                     "SELECT audit_fingerprint FROM history_import_completion WHERE completion_id=1")) {
            return rows.getString(1);
        }
    }

    private static final class ScriptedSource implements HistoricalPricePageSource {
        private final Map<Interval, ImportedPage> pages;
        private final List<FetchCall> calls = new ArrayList<>();

        private ScriptedSource(Map<Interval, ImportedPage> pages) {
            this.pages = new HashMap<>(pages);
        }

        @Override
        public ImportedPage fetch(HistoryImportRequest request, Instant from, Instant to, int maxBars) {
            calls.add(new FetchCall(from, to, maxBars));
            ImportedPage page = pages.get(new Interval(from, to));
            if (page == null) {
                throw new AssertionError("Unexpected interval " + from + " to " + to);
            }
            return page;
        }
    }

    private record Interval(Instant from, Instant to) {
    }

    public static final class ImportApplicationProcess {
        public static void main(String[] args) throws Exception {
            Class<?> application = Class.forName("io.g3tech.axetrader.AxeTraderApplication");
            var main = application.getDeclaredMethod("main", String[].class);
            main.setAccessible(true);
            main.invoke(null, (Object) args);
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class ImportTestConfiguration {
        @Bean
        @Primary
        HistoricalPricePageSource deterministicHistoryPageSource() {
            return (request, fromInclusive, toExclusive, maxBars) -> new ImportedPage(
                    fromInclusive, toExclusive,
                    completePage(fromInclusive, toExclusive).prices(),
                    "deterministic-subprocess-page");
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
