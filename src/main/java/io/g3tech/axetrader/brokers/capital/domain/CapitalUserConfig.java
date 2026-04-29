package io.g3tech.axetrader.brokers.capital.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brokers.capital.api.user")
public record CapitalUserConfig (String login, String password, String apiKey) {
}
