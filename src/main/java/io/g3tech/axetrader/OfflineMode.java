package io.g3tech.axetrader;

import io.g3tech.axetrader.config.AxeTraderMode;
import org.springframework.core.env.Environment;

import java.util.Objects;

/**
 * Identifies the batch modes that must run without a web server.
 *
 * <p>A servlet container keeps non-daemon threads alive, so an {@code ApplicationRunner} that
 * finishes its work would still leave the process running forever. Batch modes therefore start with
 * {@code WebApplicationType.NONE}, which lets the context close and the JVM exit with the runner's
 * exit code once the work is done.
 */
public final class OfflineMode {

    private OfflineMode() {
    }

    public static boolean isHeadless(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.getProperty("axe-trader.history-import.enabled", Boolean.class, false)) {
            return true;
        }
        String mode = environment.getProperty("axe-trader.mode");
        return mode != null && !mode.isBlank() && AxeTraderMode.from(mode) == AxeTraderMode.DISCOVERY;
    }
}
