package io.g3tech.axetrader.history;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HistoryReingestionServiceTest {

    private static final Instant FROM = Instant.parse("2025-01-20T16:20:00Z");

    @Test
    void probeFetchesExactlyTheRequestedRangeAndDoesNotCreateOrModifyDatabases(@TempDir Path directory)
            throws Exception {
        Path stagingDatabase = directory.resolve("staging.sqlite");
        HistoryImportRequest request = request(stagingDatabase, FROM.plusSeconds(60));
        RecordingSource source = new RecordingSource(List.of(page(FROM, FROM.plusSeconds(60), price(FROM))));
        HistoryReingestionService service = new HistoryReingestionService(source, mock(HistoryDatabasePromoter.class));

        service.probe(request);

        assertThat(source.calls).containsExactly(new PageCall(FROM, FROM.plusSeconds(60), 1_000));
        assertThat(Files.exists(stagingDatabase)).isFalse();
    }

    @Test
    void stageStopsAtTheFirstFailedAuditAndNeverInvokesPromotion(@TempDir Path directory) {
        Path stagingDatabase = directory.resolve("staging.sqlite");
        HistoryImportRequest request = request(stagingDatabase, FROM.plusSeconds(60));
        RecordingSource source = new RecordingSource(List.of(page(FROM, FROM.plusSeconds(60), invalidPrice(FROM))));
        HistoryDatabasePromoter promoter = mock(HistoryDatabasePromoter.class);
        HistoryReingestionService service = new HistoryReingestionService(source, promoter);

        assertThatThrownBy(() -> service.stage(request)).isInstanceOf(HistoryAuditFailedException.class);

        verify(promoter, never()).promote(any(), any(), any());
    }

    @Test
    void stageRequestsSequentialNonOverlappingPagesAndAdvancesFromEachPageMaximum(@TempDir Path directory) {
        Path stagingDatabase = directory.resolve("staging.sqlite");
        Instant to = FROM.plusSeconds(1_001L * 60);
        HistoryImportRequest request = request(stagingDatabase, to);
        RecordingSource source = new RecordingSource(List.of(
                page(FROM, FROM.plusSeconds(1_000L * 60), prices(FROM, 1_000)),
                page(FROM.plusSeconds(1_000L * 60), to, List.of(price(FROM.plusSeconds(1_000L * 60))))));
        HistoryReingestionService service = new HistoryReingestionService(source, mock(HistoryDatabasePromoter.class));

        HistoryImportAudit audit = service.stage(request);

        assertThat(audit.isPromotable()).isTrue();
        assertThat(source.calls).containsExactly(
                new PageCall(FROM, FROM.plusSeconds(1_000L * 60), 1_000),
                new PageCall(FROM.plusSeconds(1_000L * 60), to, 1_000));
    }

    @Test
    void stageRejectsAnEmptyPageBeforeItCanBeAuditedOrPromoted(@TempDir Path directory) {
        Path stagingDatabase = directory.resolve("staging.sqlite");
        HistoryImportRequest request = request(stagingDatabase, FROM.plusSeconds(60));
        HistoryReingestionService service = new HistoryReingestionService(
                new RecordingSource(List.of(page(FROM, FROM.plusSeconds(60), List.of()))),
                mock(HistoryDatabasePromoter.class));

        assertThatThrownBy(() -> service.stage(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty page");
    }

    @Test
    void stageRejectsAShortPageThatCannotAdvanceToTheRequestedBoundary(@TempDir Path directory) {
        Path stagingDatabase = directory.resolve("staging.sqlite");
        Instant to = FROM.plusSeconds(120);
        HistoryImportRequest request = request(stagingDatabase, to);
        RecordingSource source = new RecordingSource(List.of(page(FROM, to, price(FROM))));
        HistoryReingestionService service = new HistoryReingestionService(source, mock(HistoryDatabasePromoter.class));

        assertThatThrownBy(() -> service.stage(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("short page");

        assertThat(source.calls).containsExactly(new PageCall(FROM, to, 1_000));
    }

    @Test
    void promoteRecomputesTheStagingAuditBeforeDelegating(@TempDir Path directory) {
        Path stagingDatabase = directory.resolve("staging.sqlite");
        Path activeDatabase = directory.resolve("active.sqlite");
        HistoryImportRequest request = request(stagingDatabase, FROM.plusSeconds(60));
        new HistoryStagingStore(request).write(page(FROM, FROM.plusSeconds(60), invalidPrice(FROM)));
        HistoryDatabasePromoter promoter = mock(HistoryDatabasePromoter.class);
        HistoryReingestionService service = new HistoryReingestionService(
                new RecordingSource(List.of()), promoter);

        assertThatThrownBy(() -> service.promote(request, activeDatabase))
                .isInstanceOf(HistoryAuditFailedException.class);

        verify(promoter, never()).promote(any(), any(), any());
    }

    private static HistoryImportRequest request(Path stagingDatabase, Instant to) {
        return new HistoryImportRequest("US500", "MINUTE", FROM, to, stagingDatabase, false);
    }

    private static ImportedPage page(Instant from, Instant to, HistoricalPrice price) {
        return page(from, to, List.of(price));
    }

    private static ImportedPage page(Instant from, Instant to, List<HistoricalPrice> prices) {
        return new ImportedPage(from, to, "payload-" + from, prices);
    }

    private static List<HistoricalPrice> prices(Instant from, int count) {
        List<HistoricalPrice> prices = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            prices.add(price(from.plusSeconds(index * 60L)));
        }
        return prices;
    }

    private static HistoricalPrice price(Instant timestamp) {
        HistoricalPrice price = new HistoricalPrice();
        price.setEpic("US500");
        price.setResolution("MINUTE");
        price.setSource("capital");
        price.setSnapshotTimeUtc(timestamp);
        price.setOpenBid(6023.1);
        price.setOpenAsk(6023.2);
        price.setHighBid(6024.1);
        price.setHighAsk(6024.2);
        price.setLowBid(6022.1);
        price.setLowAsk(6022.2);
        price.setCloseBid(6023.3);
        price.setCloseAsk(6023.4);
        price.setLastTradedVolume(42);
        price.setIngestionTimeUtc(FROM);
        return price;
    }

    private static HistoricalPrice invalidPrice(Instant timestamp) {
        HistoricalPrice price = price(timestamp);
        price.setCloseBid(0.0);
        return price;
    }

    private record PageCall(Instant from, Instant to, int maxBars) {
    }

    private static final class RecordingSource implements HistoricalPricePageSource {

        private final List<ImportedPage> pages;
        private final List<PageCall> calls = new ArrayList<>();

        private RecordingSource(List<ImportedPage> pages) {
            this.pages = new ArrayList<>(pages);
        }

        @Override
        public ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars) {
            calls.add(new PageCall(fromInclusive, toExclusive, maxBars));
            return pages.removeFirst();
        }
    }
}
