package io.g3tech.axetrader.history;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayHistoryIntegrityMigrationTest {

    @TempDir
    Path directory;

    @Test
    void normalisesShortTimestampsAndEnforcesOneRowPerMinute() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("migrate.sqlite");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("1").load().migrate();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().execute("""
                    INSERT INTO historical_price VALUES
                      ('a','US500','MINUTE','2024-12-04T23:20Z',1,1,1,1,1,1,1,1,1,'capital','2024-12-04T23:21:00Z')
                    """);
        }

        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            var rows = connection.createStatement().executeQuery("SELECT snapshot_time_utc FROM historical_price");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("2024-12-04T23:20:00Z");
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO historical_price VALUES
                      ('b','US500','MINUTE','2024-12-04T23:20:00Z',1,1,1,1,1,1,1,1,1,'capital','2024-12-04T23:21:00Z')
                    """)).isInstanceOf(SQLException.class);
            var tables = connection.createStatement().executeQuery("""
                    SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN
                      ('history_import_run','price_exclusion','history_import_page','history_import_closure')
                    """);
            assertThat(tables.getLong(1)).isEqualTo(4);
        }
    }
}
