package io.g3tech.historyimport;

import io.g3tech.axetrader.brokers.capital.ApiClient;
import io.g3tech.axetrader.brokers.capital.AuthenticationClient;
import io.g3tech.axetrader.brokers.capital.domain.CapitalUserConfig;
import io.g3tech.axetrader.history.CapitalHistoricalPricePageSource;
import io.g3tech.axetrader.history.HistoryReingestionRunner;
import io.g3tech.axetrader.history.HistoryReingestionService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Minimal command context that deliberately never opens the ordinary active datasource. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@EnableConfigurationProperties(CapitalUserConfig.class)
@Import({
        ApiClient.class,
        AuthenticationClient.class,
        CapitalHistoricalPricePageSource.class,
        HistoryReingestionService.class,
        HistoryReingestionRunner.class
})
public class HistoryReingestionApplication {
}
