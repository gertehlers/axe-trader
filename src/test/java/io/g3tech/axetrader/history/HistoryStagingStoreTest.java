package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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

    private HistoryImportRequest request() {
        return new HistoryImportRequest("US500", "MINUTE", FROM, TO,
                tempDir.resolve("staging.sqlite"), "capital");
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
}
