package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.backtest.series.BarSeriesFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BrokenPriceExclusionLedgerIntegrationTest {

    private static final Path DATABASE = brokenLedgerDatabase();

    @Autowired
    private BarSeriesFactory factory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }

    @Test
    void failsWhenPresentLedgerDoesNotMatchTheReadSchema() {
        assertThatThrownBy(() -> factory.build("US500", 10, 1))
                .isInstanceOf(DataAccessException.class);
    }

    private static Path brokenLedgerDatabase() {
        try {
            Path database = Files.createTempDirectory("axe-trader-broken-price-ledger-").resolve("active.sqlite");
            String databaseUrl = "jdbc:sqlite:" + database;
            Flyway.configure().dataSource(databaseUrl, null, null).locations("classpath:db/migration").target("1").load()
                    .migrate();
            try (var connection = DriverManager.getConnection(databaseUrl);
                 var statement = connection.createStatement()) {
                statement.execute("create table price_exclusion (unsupported_column text not null)");
                insertHistoricalPrice(connection);
            }
            return database;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create broken-ledger test database", exception);
        }
    }

    private static void insertHistoricalPrice(java.sql.Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("""
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
            statement.setString(14, "broken");
            statement.setString(15, "2024-01-01T01:02:00Z");
            statement.executeUpdate();
        }
    }
}
