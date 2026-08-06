package io.g3tech.axetrader.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "axe-trader.history-import", name = "enabled", havingValue = "true")
public final class HistoryImportRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(HistoryImportRunner.class);

    private final HistoryImportProperties properties;
    private final HistoryImportService service;
    private final HistoryUpdateService updateService;
    private final HistoryDirtyDataReporter reporter;
    private final HistoryCursorReader cursorReader;
    private int exitCode;

    public HistoryImportRunner(HistoryImportProperties properties, HistoryImportService service,
                               HistoryUpdateService updateService, HistoryDirtyDataReporter reporter,
                               HistoryCursorReader cursorReader) {
        this.properties = properties;
        this.service = service;
        this.updateService = updateService;
        this.reporter = reporter;
        this.cursorReader = cursorReader;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        switch (properties.requiredMode()) {
            case PROBE -> logAudit("Probe", service.probe(properties.request(),
                    properties.activeDatabase(), properties.archive()));
            case STAGE -> logAudit("Staged import", service.stage(properties.request(),
                    properties.activeDatabase(), properties.archive()));
            case PROMOTE -> {
                Path backup = service.promote(properties.requiredStagingDatabase(),
                        properties.activeDatabase(), properties.archive());
                logger.info("Promoted staged history; legacy database backup is {}", backup);
            }
            case UPDATE -> runUpdate();
            case REPORT -> runReport();
            case ARCHIVE -> throw new IllegalStateException("Archive mode is implemented in Task 7");
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private void runUpdate() {
        List<HistoryUpdateOutcome> outcomes = updateService.update(properties.epic(), properties.resolution(),
                properties.from(), properties.activeDatabase(), properties.archive());
        long merged = outcomes.stream()
                .filter(outcome -> outcome.status() == HistoryUpdateOutcome.Status.MERGED).count();
        long failed = outcomes.stream()
                .filter(outcome -> outcome.status() == HistoryUpdateOutcome.Status.FAILED).count();
        long current = outcomes.stream()
                .filter(outcome -> outcome.status() == HistoryUpdateOutcome.Status.ALREADY_CURRENT).count();
        logger.info("Update complete: {} instrument(s) merged, {} already current, {} failed",
                merged, current, failed);
        if (failed > 0) {
            exitCode = 1;
        }
    }

    private void runReport() {
        List<HistoryTarget> targets = properties.epic() == null || properties.epic().isBlank()
                ? cursorReader.storedTargets()
                : cursorReader.storedTargets().stream()
                        .filter(target -> target.epic().equals(properties.epic()))
                        .toList();
        for (HistoryTarget target : targets) {
            HistoryDirtyDataReport report = reporter.report(properties.activeDatabase(), target,
                    properties.from(), properties.to());
            logger.info("Dirty-data report {} {} [{}, {}): stored={}, excluded={}, dirtyRate={}, byReason={}",
                    target.epic(), target.resolution(), report.fromInclusive(), report.toExclusive(),
                    report.storedMinutes(), report.excludedMinutes(),
                    String.format("%.4f%%", report.dirtyRate() * 100), report.exclusionsByReason());
        }
    }

    private static void logAudit(String operation, HistoryImportAudit audit) {
        logger.info("{} audit: requested=[{}, {}), actual=[{}, {}], received={}, accepted={}, rejected={}, "
                        + "acceptedMinutes={}, excludedMinutes={}, duplicates={}, closures={} ({} minutes), "
                        + "continuityGaps={} ({} minutes), observations={}, rawReceived={}, rawAccepted={}, "
                        + "rawRejected={}, pending={}, exclusions={}, consistent={}",
                operation, audit.requestedFrom(), audit.requestedTo(), audit.actualFrom(), audit.actualTo(),
                audit.receivedCount(), audit.acceptedCount(), audit.rejectedCount(), audit.acceptedMinuteCount(),
                audit.excludedMinuteCount(), audit.duplicateCount(), audit.recognizedSessionClosures().size(),
                audit.recognizedClosureMinuteCount(), audit.continuityGaps().size(), audit.continuityGapMinuteCount(),
                audit.observationCount(), audit.rawReceivedCount(), audit.rawAcceptedCount(), audit.rawRejectedCount(),
                audit.pendingWorkCount(), audit.exclusionsByReason(), audit.isConsistent());
    }
}
