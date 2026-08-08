package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.runner.EntryFeatureExtractor;
import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryRunServiceTest {

    private static final Instant WINDOW_FROM = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2024-02-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private DiscoveryProperties properties;
    private BacktestProperties backtest;

    @BeforeEach
    void setUp() {
        properties = new DiscoveryProperties();
        properties.setFrom(WINDOW_FROM);
        properties.setTo(WINDOW_TO);

        backtest = new BacktestProperties();
        backtest.setEpic("US500");
        backtest.setTimeframeMinutes(5);
        backtest.setStrategy(strategy());
    }

    @Test
    void refusesAWindowThatLoadsNoRows() {
        DiscoveryRunService service = service((epic, from, to) -> List.of());

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no rows");
    }

    @Test
    void refusesASeriesTooShortForTheConfiguredIndicatorPeriods() {
        DiscoveryRunService service = service((epic, from, to) -> prices(60));

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void refusesToRunWhenSourceFilesAreModifiedBecauseTheRecordedCommitWouldBeAFalseClaim() {
        DiscoveryRunService service = new DiscoveryRunService(
                (epic, from, to) -> prices(6000), new BarSeriesFactory(null), pipeline(), properties, backtest,
                () -> "deadbeef", () -> List.of("src/main/java/io/g3tech/axetrader/AxeTraderApplication.java"));

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AxeTraderApplication.java");
    }

    @Test
    void refusesAWindowThatLiesEntirelyInTheReservedTail() {
        properties.setFrom(Instant.parse("2026-05-02T00:00:00Z"));
        properties.setTo(Instant.parse("2026-06-01T00:00:00Z"));
        DiscoveryRunService service = service((epic, from, to) -> prices(6000));

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void refusesAWindowThatOverlapsTheProtectedBand() {
        properties.setTo(Instant.parse("2026-02-01T00:00:00Z"));
        DiscoveryRunService service = service((epic, from, to) -> prices(6000));

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected OOS");
    }

    @Test
    void writesAReportAndPersistsExactlyOneRunForACleanWindow() throws Exception {
        Path database = temporaryDirectory.resolve("discovery.sqlite");
        Path report = temporaryDirectory.resolve("discovery-report.json");
        properties.setPersistencePath(database);
        properties.setReportPath(report);
        DiscoveryRunService service = service((epic, from, to) -> prices(6000));

        service.run();

        assertThat(Files.exists(report)).isTrue();
        assertThat(Files.readString(report)).contains("run_key");
        assertThat(Files.exists(database)).isTrue();
    }

    @Test
    void recordsTheLogicalDigestOfTheLoadedRowsRatherThanAFileHash() {
        Path database = temporaryDirectory.resolve("discovery.sqlite");
        properties.setPersistencePath(database);
        properties.setReportPath(temporaryDirectory.resolve("report.json"));
        List<HistoricalPrice> loaded = prices(6000);
        DiscoveryRunService service = service((epic, from, to) -> loaded);

        service.run();

        assertThat(service.lastInputDataHash()).isEqualTo(DiscoveryInputDigest.of(loaded));
    }

    private DiscoveryRunService service(DiscoveryRunService.PriceWindowLoader loader) {
        return new DiscoveryRunService(loader, new BarSeriesFactory(null), pipeline(), properties, backtest,
                () -> "deadbeef", List::of);
    }

    private static DiscoveryPipeline pipeline() {
        return new DiscoveryPipeline(
                new ObservableStateExtractor(new EntryFeatureExtractor()),
                new ForwardPathLabeller(), new StrategyFactory());
    }

    private static List<HistoricalPrice> prices(int count) {
        List<HistoricalPrice> prices = new ArrayList<>(count);
        Instant time = WINDOW_FROM;
        for (int index = 0; index < count; index++) {
            double base = 5000 + Math.sin(index / 20.0) * 25;
            HistoricalPrice price = new HistoricalPrice();
            price.setEpic("US500");
            price.setResolution("MINUTE");
            price.setSnapshotTimeUtc(time);
            price.setOpenBid(base);
            price.setOpenAsk(base + 0.4);
            price.setHighBid(base + 2);
            price.setHighAsk(base + 2.4);
            price.setLowBid(base - 2);
            price.setLowAsk(base - 1.6);
            price.setCloseBid(base + 1);
            price.setCloseAsk(base + 1.4);
            price.setLastTradedVolume(100 + index % 50);
            prices.add(price);
            time = time.plus(Duration.ofMinutes(1));
        }
        return prices;
    }

    private static BacktestProperties.Strategy strategy() {
        BacktestProperties.Strategy strategy = new BacktestProperties.Strategy();
        strategy.setRsiPeriod(7);
        strategy.setRsiSmoothPeriod(7);
        strategy.setBbPeriod(20);
        strategy.setBbMultiplier(2.0);
        strategy.setEmaPeriod(50);
        strategy.setAtrPeriod(14);
        strategy.setRsiOversold(25);
        strategy.setRsiOverbought(75);
        strategy.setStopAtrMultiple(3.0);
        strategy.setTargetAtrMultiple(0.75);
        strategy.setTrendEmaPeriod(200);
        strategy.setConfluenceThreshold(3);
        strategy.setProximityAtrMultiple(0.5);
        strategy.setSwingLookbackBars(10);
        strategy.setVolumeSmaPeriod(20);
        strategy.setEnableCandles(true);
        strategy.setEnableSupportResistance(true);
        strategy.setEnableVolumeTrend(true);
        strategy.setEnableLong(true);
        return strategy;
    }
}
