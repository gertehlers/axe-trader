package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.discovery.report.DiscoveryReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Entry point for {@code --axe-trader.mode=discovery}, modelled on {@code HistoryImportRunner}.
 *
 * <p>Registered by {@link DiscoveryConfiguration}, which is itself gated on the mode property, so
 * this bean is absent entirely in every other mode.
 */
public final class DiscoveryRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryRunner.class);

    private final Supplier<DiscoveryReport> discovery;
    private int exitCode;

    public DiscoveryRunner(Supplier<DiscoveryReport> discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            DiscoveryReport report = discovery.get();
            logger.info("Discovery complete: {} pattern(s) reported", report == null ? 0 : report.patterns().size());
        } catch (RuntimeException failure) {
            // A refusal is an expected outcome, not a crash: report it and set a non-zero exit
            // code rather than letting a stack trace escape the runner.
            logger.error("Discovery run refused: {}", failure.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
