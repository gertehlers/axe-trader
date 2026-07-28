package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.discovery.session.HistoricalSessionCalendar;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit-only development entry point. Window authorization precedes all repository access. */
@SpringBootTest
class EmpiricalDiscoveryHarnessTest {

    private static final Instant DEFAULT_FROM = Instant.parse("2024-12-04T00:00:00Z");
    private static final Instant DEFAULT_TO = DiscoveryWindowPolicy.OOS_FROM;

    @Autowired private HistoricalPriceRepository repository;
    @Autowired private BarSeriesFactory barSeriesFactory;
    @Autowired private BacktestProperties properties;
    @Autowired private ObservableStateExtractor extractor;
    @Autowired private ForwardPathLabeller labeller;
    @Autowired private StrategyFactory strategyFactory;

    @Test
    void rejectsProtectedIntersectionBeforeHistoryLoad() {
        AtomicInteger reads = new AtomicInteger();
        assertThatThrownBy(() -> guardedLoad(
                Instant.parse("2025-12-31T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                (from, to) -> {
                    reads.incrementAndGet();
                    return List.of();
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected OOS");
        assertThat(reads).hasValue(0);
    }

    @Test
    @EnabledIfSystemProperty(named = "discovery", matches = "true")
    void discovery() {
        String configuredFrom = System.getProperty("discovery.from");
        String configuredTo = System.getProperty("discovery.to");
        if ((configuredFrom == null) != (configuredTo == null)) {
            throw new IllegalArgumentException("discovery.from and discovery.to must be provided together");
        }
        Instant from = configuredFrom == null ? DEFAULT_FROM : Instant.parse(configuredFrom);
        Instant to = configuredTo == null ? DEFAULT_TO : Instant.parse(configuredTo);
        List<HistoricalPrice> prices = guardedLoad(from, to,
                (guardedFrom, guardedTo) ->
                        repository.findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
                                properties.getEpic(), guardedFrom, guardedTo));
        var market = barSeriesFactory.fromPricesWithSides(
                properties.getEpic(), prices, properties.getTimeframeMinutes());
        var calendar = HistoricalSessionCalendar.fit(
                prices.stream().map(HistoricalPrice::getSnapshotTimeUtc).toList(), 10);
        Path persistence = pathProperty("discovery.persist", DiscoveryRequest.DEFAULT_PERSISTENCE_PATH);
        Path report = pathProperty("discovery.report", DiscoveryRequest.DEFAULT_REPORT_PATH);
        DiscoveryRequest request = new DiscoveryRequest(
                properties.getEpic(), properties.getTimeframeMinutes(), from, to,
                properties.getStrategy(), persistence, report, "working-tree", inputHash(prices),
                market, calendar);
        new DiscoveryPipeline(extractor, labeller, strategyFactory).run(request);
    }

    static <T> List<T> guardedLoad(Instant from, Instant to, HistoryLoader<T> loader) {
        new DiscoveryWindowPolicy().requireDevelopmentWindow(from, to);
        return loader.load(from, to);
    }

    private static Path pathProperty(String name, Path defaultPath) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() || value.equalsIgnoreCase("true")
                ? defaultPath : Path.of(value);
    }

    private static String inputHash(List<HistoricalPrice> prices) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (HistoricalPrice price : prices) {
                digest.update((price.getSnapshotTimeUtc() + "|" + price.getCloseBid() + "|"
                        + price.getCloseAsk() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash discovery input", exception);
        }
    }

    @FunctionalInterface
    interface HistoryLoader<T> {
        List<T> load(Instant from, Instant to);
    }
}
