package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PriceExclusionRepositoryIntegrationTest {

    private static final Path DATABASE = temporaryDatabase();

    @Autowired
    private BarSeriesFactory factory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @Test
    void buildReadsPopulatedV1HistoryWithoutCreatingAnExclusionLedger() {
        assertThat(ledgerTableCount()).isZero();

        BarSeries series = factory.build("US500", 10, 1);

        assertThat(series.getBarCount()).isEqualTo(1);
        assertThat(ledgerTableCount()).isZero();
    }

    private int ledgerTableCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from sqlite_master where type = 'table' and name = 'price_exclusion'", Integer.class);
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
