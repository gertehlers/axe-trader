package io.g3tech.axetrader;

import io.g3tech.axetrader.brokers.capital.domain.CapitalUserConfig;
import io.g3tech.axetrader.brokers.capital.domain.MarketDataPreferences;
import io.g3tech.axetrader.strategy.StrategyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties({CapitalUserConfig.class, MarketDataPreferences.class, StrategyProperties.class})
public class AxeTraderApplication {

	static void main(String[] args) {
		SpringApplication.run(AxeTraderApplication.class, args);
	}

}
