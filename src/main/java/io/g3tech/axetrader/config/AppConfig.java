package io.g3tech.axetrader.config;

import io.g3tech.axetrader.strategy.ConfluenceSettings;
import io.g3tech.axetrader.strategy.IndicatorSettings;
import io.g3tech.axetrader.strategy.StrategyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ConfluenceSettings confluenceSettings(StrategyProperties strategyProperties) {
        return strategyProperties.toConfluenceSettings();
    }

    @Bean
    public IndicatorSettings indicatorSettings(StrategyProperties strategyProperties) {
        return strategyProperties.toIndicatorSettings();
    }
}
