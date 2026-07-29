package io.g3tech.axetrader.history;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HistoryStagingStoreTest {

    private static final Instant FROM = Instant.parse("2025-01-20T16:20:00Z");

    @Test
    void auditFailsForCrossedCloseEvenWhenEveryPriceIsPositive(@TempDir Path directory) {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);

        store.write(page(FROM, price(FROM, 6023.4, 6023.3)));

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.isPromotable()).isFalse();
        assertThat(audit.crossedCloseCount()).isEqualTo(1);
        assertThat(audit.invalidFieldCount()).isZero();
    }

    @Test
    void repeatedPageCannotCreateDuplicateTimestampRows(@TempDir Path directory) {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);
        ImportedPage page = page(FROM, price(FROM, 6023.3, 6023.4));

        store.write(page);
        store.write(page);

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.duplicateCount()).isZero();
        assertThat(audit.rowCount()).isEqualTo(1);
        assertThat(audit.distinctTimestampCount()).isEqualTo(1);
        assertThat(audit.isPromotable()).isTrue();
    }

    @Test
    void auditCountsNonPositivePriceFieldsAsInvalid(@TempDir Path directory) {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);

        store.write(page(FROM, price(FROM, 0.0, 6023.4)));

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.invalidFieldCount()).isEqualTo(1);
        assertThat(audit.isPromotable()).isFalse();
    }

    @Test
    void auditCountsNonFinitePriceFieldsAsInvalid(@TempDir Path directory) {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);

        store.write(page(FROM, price(FROM, Double.NaN, 6023.4)));

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.invalidFieldCount()).isEqualTo(1);
        assertThat(audit.isPromotable()).isFalse();
    }

    @Test
    void stagingPreservesTheRawNonFiniteValueForSqlAudit(@TempDir Path directory) throws Exception {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);

        store.write(page(FROM, price(FROM, Double.NaN, 6023.4)));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT close_bid FROM historical_price")) {
            result.next();
            assertThat(result.getString(1)).isEqualTo("NaN");
        }
        assertThat(store.audit(request).invalidFieldCount()).isEqualTo(1);
    }

    @Test
    void auditReportsEveryBidAskCrossingIndependently(@TempDir Path directory) {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);
        HistoricalPrice price = price(FROM, 6023.5, 6023.4);
        price.setOpenBid(6023.3);
        price.setOpenAsk(6023.2);
        price.setHighBid(6024.3);
        price.setHighAsk(6024.2);
        price.setLowBid(6022.3);
        price.setLowAsk(6022.2);

        store.write(page(FROM, price));

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.crossedOpenCount()).isEqualTo(1);
        assertThat(audit.crossedHighCount()).isEqualTo(1);
        assertThat(audit.crossedLowCount()).isEqualTo(1);
        assertThat(audit.crossedCloseCount()).isEqualTo(1);
    }

    @Test
    void auditFailsForMissingWeekdayBars(@TempDir Path directory) {
        Instant to = FROM.plusSeconds(180);
        HistoryImportRequest request = request(directory, FROM, to);
        HistoryStagingStore store = new HistoryStagingStore(request);

        store.write(page(FROM, price(FROM, 6023.3, 6023.4)));
        store.write(page(FROM.plusSeconds(120), price(FROM.plusSeconds(120), 6023.3, 6023.4)));

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.coverageGapCount()).isEqualTo(1);
        assertThat(audit.isPromotable()).isFalse();
    }

    @Test
    void stagingRejectsPriceOutsideTheImportWindow(@TempDir Path directory) {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);
        Instant afterWindow = FROM.plusSeconds(60);

        assertThatIllegalArgumentException().isThrownBy(() -> store.write(page(afterWindow,
                price(afterWindow, 6023.3, 6023.4))));
    }

    @Test
    void auditRejectsCrossedRowsAnywhereInTheStagingDatabase(@TempDir Path directory) throws Exception {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);
        store.write(page(FROM, price(FROM, 6023.3, 6023.4)));
        insertRawPrice(request.stagingDatabase(), FROM.plusSeconds(60), 6023.5, 6023.4);

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.rowCount()).isEqualTo(2);
        assertThat(audit.crossedCloseCount()).isEqualTo(1);
        assertThat(audit.isPromotable()).isFalse();
    }

    @Test
    void auditRejectsOtherwiseValidRowsOutsideTheImportWindow(@TempDir Path directory) throws Exception {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);
        store.write(page(FROM, price(FROM, 6023.3, 6023.4)));
        insertRawPrice(request.stagingDatabase(), FROM.plusSeconds(60), 6023.3, 6023.4);

        assertThat(store.audit(request).isPromotable()).isFalse();
    }

    @Test
    void auditComputesDuplicateRowsAcrossTheEntireStagingDatabase(@TempDir Path directory) throws Exception {
        HistoryImportRequest request = request(directory, FROM, FROM.plusSeconds(60));
        HistoryStagingStore store = new HistoryStagingStore(request);
        store.write(page(FROM, price(FROM, 6023.3, 6023.4)));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + request.stagingDatabase());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX historical_price_source_epic_resolution_timestamp");
        }
        Instant outsideWindow = FROM.plusSeconds(60);
        insertRawPrice(request.stagingDatabase(), outsideWindow, 6023.3, 6023.4);
        insertRawPrice(request.stagingDatabase(), outsideWindow, 6023.3, 6023.4);

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.duplicateCount()).isEqualTo(1);
        assertThat(audit.isPromotable()).isFalse();
    }

    @Test
    void auditDoesNotCountWeekendClosuresAsCoverageGaps(@TempDir Path directory) {
        Instant friday = Instant.parse("2025-01-17T23:59:00Z");
        Instant monday = Instant.parse("2025-01-20T00:00:00Z");
        HistoryImportRequest request = request(directory, friday, monday);
        HistoryStagingStore store = new HistoryStagingStore(request);

        store.write(page(friday, price(friday, 6023.3, 6023.4)));

        HistoryImportAudit audit = store.audit(request);

        assertThat(audit.coverageGapCount()).isZero();
        assertThat(audit.isPromotable()).isTrue();
    }

    private static HistoryImportRequest request(Path directory, Instant from, Instant to) {
        return new HistoryImportRequest("US500", "MINUTE", from, to,
                directory.resolve("staging.sqlite"), "capital", false);
    }

    private static ImportedPage page(Instant snapshotTime, HistoricalPrice price) {
        return new ImportedPage(snapshotTime, snapshotTime.plusSeconds(60), "page-" + snapshotTime,
                List.of(price));
    }

    private static HistoricalPrice price(Instant snapshotTime, double closeBid, double closeAsk) {
        HistoricalPrice price = new HistoricalPrice();
        price.setEpic("US500");
        price.setResolution("MINUTE");
        price.setSource("capital");
        price.setSnapshotTimeUtc(snapshotTime);
        price.setOpenBid(6023.1);
        price.setOpenAsk(6023.2);
        price.setHighBid(6024.1);
        price.setHighAsk(6024.2);
        price.setLowBid(6022.1);
        price.setLowAsk(6022.2);
        price.setCloseBid(closeBid);
        price.setCloseAsk(closeAsk);
        price.setLastTradedVolume(42);
        price.setIngestionTimeUtc(FROM);
        return price;
    }

    private static void insertRawPrice(Path stagingDatabase, Instant snapshotTime, double closeBid, double closeAsk)
            throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + stagingDatabase);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO historical_price
                        (id, epic, resolution, snapshot_time_utc, open_bid, open_ask, high_bid, high_ask,
                         low_bid, low_ask, close_bid, close_ask, last_traded_volume, source, ingestion_time_utc)
                    VALUES ('raw-%s', 'US500', 'MINUTE', '%s', 6023.1, 6023.2, 6024.1, 6024.2,
                            6022.1, 6022.2, %s, %s, 42, 'capital', '%s')
                    """.formatted(java.util.UUID.randomUUID(), snapshotTime, closeBid, closeAsk, FROM));
        }
    }
}
