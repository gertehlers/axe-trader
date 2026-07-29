package io.g3tech.axetrader.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Property-gated command runner for the deliberately explicit history reingestion modes. */
@Component
@ConditionalOnProperty(prefix = "axe-trader.history-import", name = "enabled", havingValue = "true")
public class HistoryReingestionRunner implements CommandLineRunner {

    private final HistoryReingestionService service;
    private final String mode;
    private final String epic;
    private final String resolution;
    private final String from;
    private final String to;
    private final String stagingDatabase;
    private final String activeDatabase;
    private final String discoveryWindow;

    public HistoryReingestionRunner(
            HistoryReingestionService service,
            @Value("${axe-trader.history-import.mode}") String mode,
            @Value("${axe-trader.history-import.epic}") String epic,
            @Value("${axe-trader.history-import.resolution}") String resolution,
            @Value("${axe-trader.history-import.from}") String from,
            @Value("${axe-trader.history-import.to}") String to,
            @Value("${axe-trader.history-import.staging-database}") String stagingDatabase,
            @Value("${axe-trader.history-import.active-database}") String activeDatabase,
            @Value("${axe-trader.history-import.discovery-window}") String discoveryWindow) {
        this.service = Objects.requireNonNull(service, "service");
        this.mode = required(mode, "mode");
        this.epic = required(epic, "epic");
        this.resolution = required(resolution, "resolution");
        this.from = required(from, "from");
        this.to = required(to, "to");
        this.stagingDatabase = required(stagingDatabase, "staging-database");
        this.activeDatabase = required(activeDatabase, "active-database");
        this.discoveryWindow = required(discoveryWindow, "discovery-window");
    }

    @Override
    public void run(String... args) {
        HistoryImportRequest request = new HistoryImportRequest(
                epic,
                resolution,
                parseInstant(from, "from"),
                parseInstant(to, "to"),
                Path.of(stagingDatabase),
                parseBoolean(discoveryWindow));
        switch (Mode.parse(mode)) {
            case PROBE -> service.probe(request);
            case STAGE -> service.stage(request);
            case PROMOTE -> service.promote(request, Path.of(activeDatabase));
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("axe-trader.history-import." + name + " must be configured");
        }
        return value;
    }

    private static Instant parseInstant(String value, String name) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("axe-trader.history-import." + name + " must be an ISO-8601 instant", exception);
        }
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("axe-trader.history-import.discovery-window must be true or false");
    }

    private enum Mode {
        PROBE,
        STAGE,
        PROMOTE;

        private static Mode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("axe-trader.history-import.mode must be one of probe, stage, promote", exception);
            }
        }
    }
}
