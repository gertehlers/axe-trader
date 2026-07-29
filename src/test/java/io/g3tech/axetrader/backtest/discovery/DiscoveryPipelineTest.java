package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.config.Ratchet;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicy;
import io.g3tech.axetrader.backtest.discovery.session.SessionBoundary;
import io.g3tech.axetrader.backtest.discovery.session.TradingSessionCalendar;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.EntryFeatureExtractor;
import io.g3tech.axetrader.backtest.series.MarketSeries;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryPipelineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejects_development_windows_that_intersect_the_protected_oos_interval() {
        DiscoveryWindowPolicy policy = new DiscoveryWindowPolicy();
        assertThatThrownBy(() -> policy.requireDevelopmentWindow(
                Instant.parse("2025-12-31T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected OOS");
    }

    @Test
    void orchestratesSidedExtractionLabellingZonesRulesAndCompactExportUnderOneRunKey() throws Exception {
        MarketSeries market = market(300);
        Instant from = market.mid().getFirstBar().getBeginTime();
        Instant to = market.mid().getLastBar().getEndTime().plusNanos(1);
        Path database = temporaryDirectory.resolve("discovery.sqlite");
        Path reportPath = temporaryDirectory.resolve("discovery-report.json");
        DiscoveryRequest request = new DiscoveryRequest(
                "US500", 5, from, to, config(), database, reportPath, "commit", "input",
                market, calendar());

        var report = new DiscoveryPipeline(
                new ObservableStateExtractor(new EntryFeatureExtractor()),
                new ForwardPathLabeller(), new StrategyFactory(),
                (scores, zones) -> List.of(
                        CandidateRule.create(
                                Direction.LONG,
                                List.of(new RuleClause("price.close", RuleClause.Operator.GT, 0)),
                                Math.max(30, zones.size()), 1, 1),
                        CandidateRule.create(
                                Direction.SHORT,
                                List.of(new RuleClause("price.close", RuleClause.Operator.GT, 0)),
                                Math.max(30, zones.size()), 1, 1))).run(request);

        assertThat(report.run().get("run_key")).isNotNull();
        assertThat(report.run()).containsKeys(
                "total_net", "maximum_drawdown", "net_to_maximum_drawdown",
                "profitable_sampled_month_percentage");
        assertThat(report.examples()).hasSize(4)
                .allSatisfy(example -> assertThat(example.chartWindow()).hasSizeLessThanOrEqualTo(21));
        assertThat(report.examples()).extracting(example -> example.kind())
                .doesNotContain("missed-run");
        assertThat(reportPath).exists();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertThat(count(connection, "discovery_run")).isEqualTo(1);
            assertThat(count(connection, "observation")).isPositive().isEqualTo(count(connection, "forward_label"));
            assertThat(count(connection, "observation")).isEqualTo(348);
            assertThat(count(connection, "opportunity_zone")).isPositive();
            assertThat(count(connection, "candidate_rule")).isEqualTo(2);
            assertThat(count(connection, "exit_policy")).isEqualTo(2);
            assertThat(count(connection, "exit_policy")).isEqualTo(count(connection, "candidate_rule"));
            assertThat(distinctRunKeys(connection)).isEqualTo(1);
        }
    }

    @Test
    void candidateIdentityIncludesTheDiscoveryRun() {
        CandidateRule rule = CandidateRule.create(
                Direction.LONG,
                List.of(new RuleClause("price.close", RuleClause.Operator.GT, 0)),
                30, 1, 1);
        ExitPolicy policy = new ExitPolicy(
                rule,
                List.of(new ExitPolicy.Tier(1, 1)),
                new ExitPolicy.Stop(ExitPolicy.StopSource.MAE_P50, 1),
                Ratchet.NONE,
                List.of(),
                48);

        assertThat(DiscoveryPipeline.frozenId("run-one", rule, policy))
                .isNotEqualTo(DiscoveryPipeline.frozenId("run-two", rule, policy));
    }

    @Test
    void walksForwardFromThreeCompleteMonthsAndExpandsOneMonthAtATime() {
        assertThat(DiscoveryPipeline.walkForwardFolds(
                Instant.parse("2024-12-04T00:00:00Z"),
                Instant.parse("2025-06-01T00:00:00Z")))
                .containsExactly(
                        new DiscoveryPipeline.WalkForwardFold(
                                Instant.parse("2025-01-01T00:00:00Z"),
                                Instant.parse("2025-04-01T00:00:00Z"),
                                YearMonth.of(2025, 4)),
                        new DiscoveryPipeline.WalkForwardFold(
                                Instant.parse("2025-01-01T00:00:00Z"),
                                Instant.parse("2025-05-01T00:00:00Z"),
                                YearMonth.of(2025, 5)));
    }

    @Test
    void omitsFalsePositiveExampleWhenNoCandidateMatches() {
        MarketSeries market = market(300);
        DiscoveryRequest request = new DiscoveryRequest(
                "US500",
                5,
                market.mid().getFirstBar().getBeginTime(),
                market.mid().getLastBar().getEndTime().plusNanos(1),
                config(),
                temporaryDirectory.resolve("no-candidates.sqlite"),
                temporaryDirectory.resolve("no-candidates-report.json"),
                "commit",
                "input",
                market,
                calendar());

        var report = new DiscoveryPipeline(
                new ObservableStateExtractor(new EntryFeatureExtractor()),
                new ForwardPathLabeller(),
                new StrategyFactory(),
                (scores, zones) -> List.of()).run(request);

        assertThat(report.examples()).extracting(example -> example.kind())
                .doesNotContain("false-positive");
    }

    private static int count(java.sql.Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rows.getInt(1);
        }
    }

    private static int distinctRunKeys(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(DISTINCT run_key) FROM discovery_run")) {
            return rows.getInt(1);
        }
    }

    private static MarketSeries market(int count) {
        BarSeries mid = new BaseBarSeriesBuilder().withName("mid").build();
        BarSeries bid = new BaseBarSeriesBuilder().withName("bid").build();
        BarSeries ask = new BaseBarSeriesBuilder().withName("ask").build();
        Instant start = Instant.parse("2025-06-01T00:00:00Z");
        for (int index = 0; index < count; index++) {
            Instant end = start.plus(Duration.ofMinutes(5L * (index + 1)));
            double close = 100 + (((index * 73) % 101) - 50) * 0.08
                    + Math.sin(index * 0.37);
            double volume = 100 + index % 17;
            add(mid, end, close, volume);
            add(bid, end, close - 0.2, volume);
            add(ask, end, close + 0.2, volume);
        }
        return new MarketSeries(mid, bid, ask);
    }

    private static void add(BarSeries series, Instant end, double close, double volume) {
        series.barBuilder().timePeriod(Duration.ofMinutes(5))
                .endTime(end)
                .openPrice(close - 0.1).highPrice(close + 0.6).lowPrice(close - 0.6)
                .closePrice(close).volume(volume).add();
    }

    private static TradingSessionCalendar calendar() {
        return new TradingSessionCalendar() {
            @Override public Optional<SessionBoundary> boundaryAfter(Instant barTime) {
                return Optional.of(new SessionBoundary(
                        barTime.plus(Duration.ofMinutes(75)), barTime.plus(Duration.ofMinutes(80))));
            }
            @Override public int minutesToClose(Instant barTime) { return 75; }
        };
    }

    private static BacktestProperties.Strategy config() {
        BacktestProperties.Strategy config = new BacktestProperties.Strategy();
        config.setRsiPeriod(2);
        config.setRsiSmoothPeriod(1);
        config.setBbPeriod(2);
        config.setBbMultiplier(2);
        config.setEmaPeriod(1);
        config.setAtrPeriod(14);
        config.setRsiOversold(25);
        config.setRsiOverbought(75);
        config.setStopAtrMultiple(3);
        config.setTargetAtrMultiple(1);
        config.setTrendEmaPeriod(0);
        config.setConfluenceThreshold(1);
        config.setProximityAtrMultiple(0.5);
        config.setSwingLookbackBars(2);
        config.setVolumeSmaPeriod(1);
        config.setEnableCandles(true);
        config.setEnableSupportResistance(true);
        config.setEnableStructure(true);
        config.setEnableVolumeTrend(true);
        config.setEnableLong(true);
        config.setEnableShort(true);
        return config;
    }
}
