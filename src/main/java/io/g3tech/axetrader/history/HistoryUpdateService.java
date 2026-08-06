package io.g3tech.axetrader.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tops each stored instrument up from its own cursor to the last completed UTC minute.
 *
 * <p>Deliberately carries no stereotype annotation: {@code HistoryUpdateConfiguration} is its only
 * bean source.
 */
public class HistoryUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryUpdateService.class);
    private static final String DEFAULT_SOURCE = "capital";
    private static final String DEFAULT_RESOLUTION = "MINUTE";

    private final HistoryCursorReader cursorReader;
    private final HistoryImportService importService;
    private final HistoryDeltaMerger merger;
    private final Path stagingDirectory;
    private final Supplier<Instant> clock;

    public HistoryUpdateService(HistoryCursorReader cursorReader, HistoryImportService importService,
                                HistoryDeltaMerger merger, Path stagingDirectory, Supplier<Instant> clock) {
        this.cursorReader = Objects.requireNonNull(cursorReader, "cursorReader");
        this.importService = Objects.requireNonNull(importService, "importService");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<HistoryUpdateOutcome> update(String epic, String resolution, Instant configuredFrom,
                                             Path activeDatabase, Path archive) {
        List<HistoryTarget> targets = resolveTargets(epic, resolution);
        Instant now = clock.get();
        List<HistoryUpdateOutcome> outcomes = new ArrayList<>();
        for (HistoryTarget target : targets) {
            outcomes.add(updateOne(target, configuredFrom, now, activeDatabase, archive));
        }
        return List.copyOf(outcomes);
    }

    /**
     * An unknown epic is deliberately returned as a seed target so the window resolver produces the
     * "explicit from" failure rather than this method silently returning nothing.
     */
    private List<HistoryTarget> resolveTargets(String epic, String resolution) {
        List<HistoryTarget> stored = cursorReader.storedTargets();
        if (epic == null || epic.isBlank()) {
            return stored;
        }
        List<HistoryTarget> matching = stored.stream()
                .filter(target -> target.epic().equals(epic))
                .filter(target -> resolution == null || resolution.isBlank()
                        || target.resolution().equals(resolution))
                .toList();
        if (!matching.isEmpty()) {
            return matching;
        }
        return List.of(new HistoryTarget(DEFAULT_SOURCE, epic,
                resolution == null || resolution.isBlank() ? DEFAULT_RESOLUTION : resolution));
    }

    private HistoryUpdateOutcome updateOne(HistoryTarget target, Instant configuredFrom, Instant now,
                                           Path activeDatabase, Path archive) {
        Optional<Instant> cursor = cursorReader.lastStoredMinute(target);
        Optional<HistoryUpdateWindow.Window> window =
                HistoryUpdateWindow.resolve(cursor, Optional.ofNullable(configuredFrom), now);
        if (window.isEmpty()) {
            logger.info("{} {} is already current at {}", target.epic(), target.resolution(),
                    cursor.map(Instant::toString).orElse("unknown"));
            return HistoryUpdateOutcome.alreadyCurrent(target);
        }

        Instant from = window.get().fromInclusive();
        Instant to = window.get().toExclusive();
        Path staging = stagingPath(target);
        HistoryImportRequest request =
                new HistoryImportRequest(target.epic(), target.resolution(), from, to, staging, target.source());
        try {
            HistoryImportAudit audit = importService.stage(request, activeDatabase, archive);
            HistoryDeltaMerger.MergeResult merge = merger.merge(staging, activeDatabase);
            deleteQuietly(staging);
            logger.info("{} {} merged [{}, {}): accepted={}, excluded={}, exclusions={}, "
                            + "closures={}, rowsMerged={}, exclusionsMerged={}",
                    target.epic(), target.resolution(), from, to, audit.acceptedMinuteCount(),
                    audit.excludedMinuteCount(), audit.exclusionsByReason(),
                    audit.recognizedSessionClosures().size(), merge.pricesMerged(), merge.exclusionsMerged());
            return HistoryUpdateOutcome.merged(target, from, to, audit, merge);
        } catch (RuntimeException exception) {
            logger.error("{} {} failed over [{}, {}); staging retained at {}",
                    target.epic(), target.resolution(), from, to, staging, exception);
            return HistoryUpdateOutcome.failed(target, from, to, exception.getMessage());
        }
    }

    private Path stagingPath(HistoryTarget target) {
        try {
            Files.createDirectories(stagingDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the staging directory " + stagingDirectory, exception);
        }
        return stagingDirectory.resolve(target.epic() + "-" + target.resolution() + "-"
                + UUID.randomUUID() + ".sqlite");
    }

    private static void deleteQuietly(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException exception) {
            logger.warn("Merged delta but could not remove the staging file {}", staging, exception);
        }
    }
}
