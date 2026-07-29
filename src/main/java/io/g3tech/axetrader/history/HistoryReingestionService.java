package io.g3tech.axetrader.history;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/** Executes the explicit, bounded probe, staging, and promotion history-import operations. */
@Service
public class HistoryReingestionService {

    static final int MAX_BARS_PER_PAGE = 1_000;

    private static final Logger logger = LoggerFactory.getLogger(HistoryReingestionService.class);

    private final HistoricalPricePageSource pageSource;
    private final HistoryDatabasePromoter promoter;

    @Autowired
    public HistoryReingestionService(HistoricalPricePageSource pageSource) {
        this(pageSource, new HistoryDatabasePromoter());
    }

    public HistoryReingestionService(HistoricalPricePageSource pageSource, HistoryDatabasePromoter promoter) {
        this.pageSource = Objects.requireNonNull(pageSource, "pageSource");
        this.promoter = Objects.requireNonNull(promoter, "promoter");
    }

    /** Fetches precisely one requested page and intentionally never opens a database. */
    public ImportedPage probe(HistoryImportRequest request) {
        Objects.requireNonNull(request, "request");
        ImportedPage page = pageSource.fetch(request, request.from(), request.to(), MAX_BARS_PER_PAGE);
        logger.info("History probe returned {} prices for [{} , {}) with payload hash {}",
                page.prices().size(), page.requestedFrom(), page.requestedTo(), page.payloadHash());
        return page;
    }

    /** Fetches and stages sequential source pages, then returns only a promotable audit. */
    public HistoryImportAudit stage(HistoryImportRequest request) {
        Objects.requireNonNull(request, "request");
        HistoryStagingStore stagingStore = new HistoryStagingStore(request);
        Duration resolution = resolutionDuration(request.resolution());
        Instant cursor = request.from();

        while (cursor.isBefore(request.to())) {
            Instant pageTo = pageEnd(cursor, request.to(), resolution);
            ImportedPage page = pageSource.fetch(request, cursor, pageTo, MAX_BARS_PER_PAGE);
            Instant nextCursor = validateAndAdvance(page, cursor, pageTo, request, resolution);
            stagingStore.write(page);
            cursor = nextCursor;
        }

        HistoryImportAudit audit = stagingStore.audit(request);
        if (!audit.isPromotable()) {
            throw new HistoryAuditFailedException(audit);
        }
        logger.info("History staging audit passed: {}", audit);
        return audit;
    }

    /** Recomputes the staged audit immediately before allowing the active database to be replaced. */
    public Path promote(HistoryImportRequest request, Path activeDatabase) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(activeDatabase, "activeDatabase");
        if (!Files.isRegularFile(request.stagingDatabase())) {
            throw new IllegalStateException("staging database does not exist: " + request.stagingDatabase());
        }

        HistoryImportAudit audit = new HistoryStagingStore(request.stagingDatabase()).audit(request);
        if (!audit.isPromotable()) {
            throw new HistoryAuditFailedException(audit);
        }
        Path backup = promoter.promote(request.stagingDatabase(), activeDatabase, audit);
        logger.info("History database promoted; backup written to {}", backup);
        return backup;
    }

    private static Instant pageEnd(Instant from, Instant overallTo, Duration resolution) {
        Instant cappedEnd;
        try {
            cappedEnd = from.plus(resolution.multipliedBy(MAX_BARS_PER_PAGE));
        } catch (ArithmeticException exception) {
            cappedEnd = overallTo;
        }
        return cappedEnd.isBefore(overallTo) ? cappedEnd : overallTo;
    }

    private static Instant validateAndAdvance(
            ImportedPage page,
            Instant expectedFrom,
            Instant expectedTo,
            HistoryImportRequest request,
            Duration resolution) {
        if (page == null) {
            throw new IllegalStateException("history page source returned no page");
        }
        if (!expectedFrom.equals(page.requestedFrom()) || !expectedTo.equals(page.requestedTo())) {
            throw new IllegalStateException("history page source returned mismatched requested bounds");
        }
        if (page.prices().isEmpty()) {
            throw new IllegalStateException("history page source returned an empty page");
        }
        if (page.prices().size() > MAX_BARS_PER_PAGE) {
            throw new IllegalStateException("history page source exceeded the maximum page size");
        }
        Instant latest = page.prices().stream()
                .map(price -> snapshotTime(price, expectedFrom, expectedTo))
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("history page source returned an empty page"));
        Instant next = latest.plus(resolution);
        if (!next.isAfter(expectedFrom)) {
            throw new IllegalStateException("history page source did not advance the import cursor");
        }
        if (page.prices().size() < MAX_BARS_PER_PAGE && !next.equals(expectedTo)) {
            throw new IllegalStateException("history page source returned a short page before its requested boundary");
        }
        if (next.isAfter(request.to())) {
            throw new IllegalStateException("history page source advanced beyond the requested import window");
        }
        return next;
    }

    private static Instant snapshotTime(HistoricalPrice price, Instant from, Instant to) {
        if (price == null || price.getSnapshotTimeUtc() == null) {
            throw new IllegalStateException("history page source returned a price without a timestamp");
        }
        Instant timestamp = price.getSnapshotTimeUtc();
        if (timestamp.isBefore(from) || !timestamp.isBefore(to)) {
            throw new IllegalStateException("history page source returned a price outside its requested page");
        }
        return timestamp;
    }

    private static Duration resolutionDuration(String resolution) {
        return CapitalHistoryResolution.requireSupported(resolution).duration();
    }
}
