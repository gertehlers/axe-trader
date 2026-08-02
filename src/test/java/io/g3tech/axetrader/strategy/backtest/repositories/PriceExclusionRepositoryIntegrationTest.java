package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.ta4j.core.BarSeries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PriceExclusionRepositoryIntegrationTest {

    private static final Path DATABASE = temporaryDatabase();

    @Autowired
    private BarSeriesFactory factory;

    @Autowired
    private HistoricalPriceRepository historicalPriceRepository;

    @Autowired
    private PriceExclusionRepository priceExclusionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @Test
    void migrationCreatesLedgerBeforeBuildReadsPreviouslyLedgerlessPriceHistory() {
        BarSeries series = factory.build("US500", 10, 1);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sqlite_master where type = 'table' and name = 'price_exclusion'", Integer.class))
                .isEqualTo(1);
        assertThat(series.getBarCount()).isEqualTo(1);
    }

    @Test
    void findsOnlyMatchingMinutesWithinTheHalfOpenEpicAndResolutionRange() {
        insertExclusion("US500", "MINUTE", "2024-01-01T00:00:00Z");
        insertExclusion("US500", "MINUTE", "2024-01-01T00:01:00Z");
        insertExclusion("US500", "MINUTE", "2024-01-01T00:02:00Z");
        insertExclusion("US500", "HOUR", "2024-01-01T00:01:00Z");
        insertExclusion("DE40", "MINUTE", "2024-01-01T00:01:00Z");

        List<String> timestamps = priceExclusionRepository.findDistinctSnapshotTimes(
                "US500", "MINUTE", "2024-01-01T00:01:00Z", "2024-01-01T00:02:00Z");

        assertThat(timestamps).containsExactly("2024-01-01T00:01:00Z");
    }

    @Test
    void buildOmitsMinuteWhoseLedgerEntryFallsWithinItsLoadedRange() {
        historicalPriceRepository.save(price("DE40", "2024-01-01T01:10:00Z"));
        insertExclusion("DE40", "MINUTE", "2024-01-01T01:10:00Z");

        BarSeries series = factory.build("DE40", 10, 1);

        assertThat(series.getBarCount()).isZero();
    }

    private void insertExclusion(String epic, String resolution, String timestamp) {
        jdbcTemplate.update("""
                        insert into price_exclusion (
                            import_run_id, source, epic, resolution, snapshot_time_utc, reason, detected_at_utc)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """,
                "test-run-" + epic + resolution + timestamp, "test", epic, resolution, timestamp,
                "TEST", "2024-01-01T01:00:00Z");
    }

    private static HistoricalPrice price(String epic, String timestamp) {
        HistoricalPrice price = new HistoricalPrice();
        price.setEpic(epic);
        price.setResolution("MINUTE");
        price.setSnapshotTimeUtc(Instant.parse(timestamp));
        price.setOpenBid(99.0);
        price.setHighBid(99.0);
        price.setLowBid(99.0);
        price.setCloseBid(99.0);
        price.setOpenAsk(101.0);
        price.setHighAsk(101.0);
        price.setLowAsk(101.0);
        price.setCloseAsk(101.0);
        price.setLastTradedVolume(1);
        price.setSource("test");
        price.setIngestionTimeUtc(Instant.parse("2024-01-01T01:00:00Z"));
        return price;
    }

    private static Path temporaryDatabase() {
        try {
            Path database = Files.createTempDirectory("axe-trader-price-exclusions-").resolve("active.sqlite");
            String databaseUrl = "jdbc:sqlite:" + database;
            Flyway.configure().dataSource(databaseUrl, null, null).locations("classpath:db/migration").target("1").load()
                    .migrate();
            try (var connection = DriverManager.getConnection(databaseUrl);
                 var statement = connection.prepareStatement("""
                         insert into historical_price (
                             id, epic, resolution, snapshot_time_utc,
                             open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask,
                             last_traded_volume, source, ingestion_time_utc)
                         values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, "US500");
                statement.setString(3, "MINUTE");
                statement.setString(4, "2024-01-01T01:01:00Z");
                for (int index = 5; index <= 12; index++) {
                    statement.setDouble(index, index % 2 == 1 ? 99.0 : 101.0);
                }
                statement.setInt(13, 1);
                statement.setString(14, "baseline");
                statement.setString(15, "2024-01-01T01:02:00Z");
                statement.executeUpdate();
            }
            return database;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create populated V1 test database", exception);
        }
    }
}
