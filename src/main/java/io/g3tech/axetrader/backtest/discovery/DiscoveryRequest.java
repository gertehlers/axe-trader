package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.session.TradingSessionCalendar;
import io.g3tech.axetrader.backtest.series.MarketSeries;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Immutable input to a development-only empirical discovery run. */
public record DiscoveryRequest(
        String instrument,
        int timeframeMinutes,
        Instant from,
        Instant to,
        BacktestProperties.Strategy strategyConfig,
        Path persistencePath,
        Path reportPath,
        String sourceCommit,
        String inputDataHash,
        MarketSeries market,
        TradingSessionCalendar sessionCalendar) {

    public static final Path DEFAULT_PERSISTENCE_PATH = Path.of("experiments/discovery.sqlite");
    public static final Path DEFAULT_REPORT_PATH = Path.of("dashboard/discovery-report.json");

    public DiscoveryRequest {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(strategyConfig, "strategyConfig");
        persistencePath = persistencePath == null ? DEFAULT_PERSISTENCE_PATH : persistencePath;
        reportPath = reportPath == null ? DEFAULT_REPORT_PATH : reportPath;
        Objects.requireNonNull(sourceCommit, "sourceCommit");
        Objects.requireNonNull(inputDataHash, "inputDataHash");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(sessionCalendar, "sessionCalendar");
        if (instrument.isBlank() || sourceCommit.isBlank() || inputDataHash.isBlank() || timeframeMinutes <= 0
                || !from.isBefore(to)) {
            throw new IllegalArgumentException("discovery request requires a non-empty half-open development window");
        }
    }
}
