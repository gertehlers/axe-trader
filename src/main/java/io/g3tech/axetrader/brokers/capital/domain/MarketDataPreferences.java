package io.g3tech.axetrader.brokers.capital.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "axe-trader.market")
public record MarketDataPreferences(List<String> epics, List<String> resolutions) {

    public MarketDataPreferences {
        epics = List.copyOf(requireNotEmpty(epics, "epics"));
        resolutions = List.copyOf(requireNotEmpty(resolutions, "resolutions"));
    }

    private static List<String> requireNotEmpty(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("axe-trader.market.%s must be configured".formatted(fieldName));
        }

        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("axe-trader.market.%s must not contain blank values".formatted(fieldName));
        }

        return values;
    }
}
