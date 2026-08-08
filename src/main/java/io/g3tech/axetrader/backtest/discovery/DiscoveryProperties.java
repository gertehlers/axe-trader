package io.g3tech.axetrader.backtest.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Development window and artifact locations for a discovery run.
 *
 * <p>Instrument, timeframe and strategy deliberately come from {@code backtest.*} instead, so a
 * discovery run and a backtest describe the same instrument by construction. {@code backtest.limit}
 * is not used: discovery is window-bounded, and a row cap would silently truncate the window while
 * the run still recorded the full range as its provenance.
 */
@ConfigurationProperties(prefix = "axe-trader.discovery")
public class DiscoveryProperties {

    private Instant from;
    private Instant to;
    private Path persistencePath;
    private Path reportPath;

    public Instant getFrom() {
        return from;
    }

    public void setFrom(Instant from) {
        this.from = from;
    }

    public Instant getTo() {
        return to;
    }

    public void setTo(Instant to) {
        this.to = to;
    }

    /** Null leaves {@link DiscoveryRequest} to supply its own default. */
    public Path getPersistencePath() {
        return persistencePath;
    }

    public void setPersistencePath(Path persistencePath) {
        this.persistencePath = persistencePath;
    }

    /** Null leaves {@link DiscoveryRequest} to supply its own default. */
    public Path getReportPath() {
        return reportPath;
    }

    public void setReportPath(Path reportPath) {
        this.reportPath = reportPath;
    }
}
