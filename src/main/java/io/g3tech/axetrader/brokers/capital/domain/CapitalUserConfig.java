package io.g3tech.axetrader.brokers.capital.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brokers.capital.api.user")
public record CapitalUserConfig(String login, String password, String apiKey) {

    public CapitalUserConfig {
        requireConfigured(login, "CAPITAL_API_USER");
        requireConfigured(password, "CAPITAL_API_PASSWORD");
        requireConfigured(apiKey, "CAPITAL_API_KEY");
    }

    private static void requireConfigured(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("%s must be configured".formatted(environmentVariable));
        }
    }
}
