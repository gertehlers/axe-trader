package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.report.DiscoveryReport;
import io.g3tech.axetrader.backtest.runner.EntryFeatureExtractor;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * Wires the discovery pipeline for {@code --axe-trader.mode=discovery} only.
 *
 * <p>{@code DiscoveryPipeline} is deliberately not annotated as a component: it constructs its own
 * collaborators and knows nothing about Spring, so the wiring lives here instead.
 */
@Configuration
@ConditionalOnProperty(prefix = "axe-trader", name = "mode", havingValue = "discovery")
public class DiscoveryConfiguration {

    @Bean
    public DiscoveryPipeline discoveryPipeline(
            EntryFeatureExtractor entryFeatureExtractor, StrategyFactory strategyFactory) {
        return new DiscoveryPipeline(
                new ObservableStateExtractor(entryFeatureExtractor), new ForwardPathLabeller(), strategyFactory);
    }

    @Bean
    public DiscoveryRunService discoveryRunService(
            HistoricalPriceRepository repository,
            BarSeriesFactory barSeriesFactory,
            DiscoveryPipeline pipeline,
            DiscoveryProperties properties,
            BacktestProperties backtest) {
        return new DiscoveryRunService(
                (epic, from, to) -> repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                        epic, from, to),
                barSeriesFactory,
                pipeline,
                properties,
                backtest,
                SourceProvenance::currentCommit,
                SourceProvenance::blockingChanges);
    }

    @Bean
    public DiscoveryRunner discoveryRunner(DiscoveryRunService service) {
        Supplier<DiscoveryReport> run = service::run;
        return new DiscoveryRunner(run);
    }
}
