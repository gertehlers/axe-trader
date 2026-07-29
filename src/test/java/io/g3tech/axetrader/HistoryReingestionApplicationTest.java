package io.g3tech.axetrader;

import io.g3tech.historyimport.HistoryReingestionApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryReingestionApplicationTest {

    @Test
    void commandLineEnablementSelectsTheIsolatedHistoryImportApplicationPath() {
        assertThat(AxeTraderApplication.historyImportEnabled(
                new String[]{"--axe-trader.history-import.enabled=true"})).isTrue();
    }

    @Test
    void explicitHistoryCommandContextDoesNotCreateAnOrdinaryDatasourceOrFlyway() {
        new ApplicationContextRunner()
                .withUserConfiguration(HistoryReingestionApplication.class)
                .withPropertyValues(
                        "axe-trader.history-import.enabled=true",
                        "axe-trader.history-import.mode=probe",
                        "axe-trader.history-import.epic=US500",
                        "axe-trader.history-import.resolution=MINUTE",
                        "axe-trader.history-import.from=2025-01-20T16:20:00Z",
                        "axe-trader.history-import.to=2025-01-20T16:21:00Z",
                        "axe-trader.history-import.staging-database=target/staging.sqlite",
                        "axe-trader.history-import.active-database=target/active.sqlite",
                        "axe-trader.history-import.discovery-window=false",
                        "brokers.capital.api.url=https://example.invalid",
                        "brokers.capital.api.user.login=test-user",
                        "brokers.capital.api.user.password=test-password",
                        "brokers.capital.api.user.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DataSource.class);
                    assertThat(context).doesNotHaveBean(Flyway.class);
                });
    }
}
