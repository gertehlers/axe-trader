package io.g3tech.axetrader.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(prefix = "axe-trader.history-import", name = "enabled", havingValue = "true")
public final class HistoryImportRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(HistoryImportRunner.class);

    private final HistoryImportProperties properties;
    private final HistoryImportService service;

    public HistoryImportRunner(HistoryImportProperties properties, HistoryImportService service) {
        this.properties = properties;
        this.service = service;
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
        }
    }

    private static void logAudit(String operation, HistoryImportAudit audit) {
        logger.info("{} audit: requested=[{}, {}), actual=[{}, {}], received={}, accepted={}, rejected={}, "
                        + "acceptedMinutes={}, excludedMinutes={}, duplicates={}, closures={} ({} minutes), "
                        + "continuityGaps={} ({} minutes), exclusions={}, consistent={}",
                operation, audit.requestedFrom(), audit.requestedTo(), audit.actualFrom(), audit.actualTo(),
                audit.receivedCount(), audit.acceptedCount(), audit.rejectedCount(), audit.acceptedMinuteCount(),
                audit.excludedMinuteCount(), audit.duplicateCount(), audit.recognizedSessionClosures().size(),
                audit.recognizedClosureMinuteCount(), audit.continuityGaps().size(), audit.continuityGapMinuteCount(),
                audit.exclusionsByReason(), audit.isConsistent());
    }
}
