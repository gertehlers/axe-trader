package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.report.DiscoveryReport;
import io.g3tech.axetrader.backtest.discovery.session.HistoricalSessionCalendar;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds one {@link DiscoveryRequest} from configuration and stored prices, then runs the discovery
 * pipeline over it.
 *
 * <p>Every refusal happens before the pipeline is entered, so a rejected run leaves no partial rows
 * behind in the discovery store.
 */
public final class DiscoveryRunService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryRunService.class);
    private static final int SESSION_BOUNDARY_OCCURRENCES = 10;

    /** Narrow seam over the price repository, so the service is testable without a database. */
    @FunctionalInterface
    public interface PriceWindowLoader {
        List<HistoricalPrice> load(String epic, Instant from, Instant to);
    }

    private final PriceWindowLoader loader;
    private final BarSeriesFactory barSeriesFactory;
    private final DiscoveryPipeline pipeline;
    private final DiscoveryProperties properties;
    private final BacktestProperties backtest;
    private final Supplier<String> commitSupplier;
    private final Supplier<List<String>> blockingChangesSupplier;
    private String lastInputDataHash;

    public DiscoveryRunService(
            PriceWindowLoader loader,
            BarSeriesFactory barSeriesFactory,
            DiscoveryPipeline pipeline,
            DiscoveryProperties properties,
            BacktestProperties backtest,
            Supplier<String> commitSupplier,
            Supplier<List<String>> blockingChangesSupplier) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.barSeriesFactory = Objects.requireNonNull(barSeriesFactory, "barSeriesFactory");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.backtest = Objects.requireNonNull(backtest, "backtest");
        this.commitSupplier = Objects.requireNonNull(commitSupplier, "commitSupplier");
        this.blockingChangesSupplier = Objects.requireNonNull(blockingChangesSupplier, "blockingChangesSupplier");
    }

    public DiscoveryReport run() {
        Instant from = Objects.requireNonNull(properties.getFrom(), "axe-trader.discovery.from");
        Instant to = Objects.requireNonNull(properties.getTo(), "axe-trader.discovery.to");
        String epic = backtest.getEpic();

        // Window legality first: the cheapest refusal, and the one that protects reserved data.
        new DiscoveryWindowPolicy().requireDevelopmentWindow(from, to);
        requireCleanSource();

        List<HistoricalPrice> prices = loader.load(epic, from, to);
        if (prices.isEmpty()) {
            throw new IllegalStateException(
                    "Discovery window " + from + " to " + to + " loaded no rows for " + epic
                            + "; check the epic and that the window has been imported");
        }

        MarketSeries market = barSeriesFactory.fromPricesWithSides(epic, prices, backtest.getTimeframeMinutes());
        int bars = market.mid().getBarCount();
        int required = minimumBars();
        if (bars < required) {
            throw new IllegalStateException("Aggregated series is too short for the configured indicator periods: "
                    + bars + " bars from " + prices.size() + " rows, need at least " + required);
        }

        lastInputDataHash = DiscoveryInputDigest.of(prices);
        logger.info("Discovery run over {} {} bars ({} rows) from {} to {}",
                bars, backtest.getTimeframeMinutes() + "m", prices.size(), from, to);

        DiscoveryRequest request = new DiscoveryRequest(
                epic,
                backtest.getTimeframeMinutes(),
                from,
                to,
                backtest.getStrategy(),
                properties.getPersistencePath(),
                properties.getReportPath(),
                commitSupplier.get(),
                lastInputDataHash,
                market,
                HistoricalSessionCalendar.fit(
                        prices.stream().map(HistoricalPrice::getSnapshotTimeUtc).toList(),
                        SESSION_BOUNDARY_OCCURRENCES));

        return pipeline.run(request);
    }

    /** The digest recorded by the most recent run; exposed so a caller can log or assert on it. */
    public String lastInputDataHash() {
        return lastInputDataHash;
    }

    private void requireCleanSource() {
        List<String> blocking = blockingChangesSupplier.get();
        if (!blocking.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to record a source commit that does not match the working tree; modified: "
                            + String.join(", ", blocking));
        }
    }

    /**
     * The longest lookback any configured indicator needs, so a run cannot start on a series that
     * cannot produce a single valid observation.
     */
    private int minimumBars() {
        BacktestProperties.Strategy strategy = backtest.getStrategy();
        return Math.max(
                Math.max(strategy.getTrendEmaPeriod(), strategy.getEmaPeriod()),
                Math.max(
                        Math.max(strategy.getBbPeriod(), strategy.getAtrPeriod()),
                        Math.max(strategy.getVolumeSmaPeriod(),
                                strategy.getRsiPeriod() + strategy.getRsiSmoothPeriod())));
    }
}
