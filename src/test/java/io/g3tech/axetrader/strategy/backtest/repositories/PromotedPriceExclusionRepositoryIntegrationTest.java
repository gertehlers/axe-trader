package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import io.g3tech.axetrader.history.HistoryStagingStore;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PromotedPriceExclusionRepositoryIntegrationTest {

    private static final Path DATABASE = promotedDatabase();

    @Autowired
    private BarSeriesFactory factory;

    @Autowired
    private PriceExclusionRepository priceExclusionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @Test
    void readsOnlyMatchingMinutesWithinTheHalfOpenEpicAndResolutionRange() {
        List<String> timestamps = priceExclusionRepository.findDistinctSnapshotTimes(
                "US500", "MINUTE", "2024-01-01T00:01:00Z", "2024-01-01T00:02:00Z");

        assertThat(timestamps).containsExactly("2024-01-01T00:01:00Z");
    }

    @Test
    void buildOmitsMinuteWhosePromotedLedgerEntryFallsWithinItsLoadedRange() {
        BarSeries series = factory.build("DE40", 10, 1);

        assertThat(series.getBarCount()).isZero();
    }

    @Test
    void stagingSchemaProvidesTheLedgerLookupIndex() {
        Integer indexCount = jdbcTemplate.queryForObject("""
                select count(*) from sqlite_master
                where type = 'index' and name = 'price_exclusion_epic_resolution_timestamp'
                """, Integer.class);

        assertThat(indexCount).isEqualTo(1);
    }

    private static Path promotedDatabase() {
        try {
            Path database = Files.createTempDirectory("axe-trader-promoted-price-history-").resolve("active.sqlite");
            try (HistoryStagingStore ignored = HistoryStagingStore.open(database)) {
                // The staging schema is the promoted clean database contract.
            }
            String databaseUrl = "jdbc:sqlite:" + database;
            try (var connection = DriverManager.getConnection(databaseUrl)) {
                insertHistoricalPrice(connection, "DE40", "2024-01-01T01:10:00Z");
                insertExclusion(connection, "US500", "MINUTE", "2024-01-01T00:00:00Z");
                insertExclusion(connection, "US500", "MINUTE", "2024-01-01T00:01:00Z");
                insertExclusion(connection, "US500", "MINUTE", "2024-01-01T00:02:00Z");
                insertExclusion(connection, "US500", "HOUR", "2024-01-01T00:01:00Z");
                insertExclusion(connection, "DE40", "MINUTE", "2024-01-01T00:01:00Z");
                insertExclusion(connection, "DE40", "MINUTE", "2024-01-01T01:10:00Z");
            }
            return database;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create promoted test database", exception);
        }
    }

    private static void insertHistoricalPrice(java.sql.Connection connection, String epic, String timestamp) throws Exception {
        try (var statement = connection.prepareStatement("""
                insert into historical_price (
                    id, epic, resolution, snapshot_time_utc,
                    open_bid, open_ask, high_bid, high_ask, low_bid, low_ask, close_bid, close_ask,
                    last_traded_volume, source, ingestion_time_utc)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, epic);
            statement.setString(3, "MINUTE");
            statement.setString(4, timestamp);
            for (int index = 5; index <= 12; index++) {
                statement.setDouble(index, index % 2 == 1 ? 99.0 : 101.0);
            }
            statement.setInt(13, 1);
            statement.setString(14, "staging");
            statement.setString(15, "2024-01-01T01:11:00Z");
            statement.executeUpdate();
        }
    }

    private static void insertExclusion(java.sql.Connection connection, String epic, String resolution, String timestamp)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                insert into price_exclusion (
                    import_run_id, source, epic, resolution, snapshot_time_utc, reason, detected_at_utc)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, "test-run-" + epic + resolution + timestamp);
            statement.setString(2, "staging");
            statement.setString(3, epic);
            statement.setString(4, resolution);
            statement.setString(5, timestamp);
            statement.setString(6, "TEST");
            statement.setString(7, "2024-01-01T01:00:00Z");
            statement.executeUpdate();
        }
    }
}
