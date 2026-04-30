package io.g3tech.axetrader.brokers.capital.dto.ws.market;

import java.util.List;

public record MarketDataSubscribe(List<String> epics, List<String> resolutions, List<String> types) {

    public static MarketDataSubscribe ohlc(List<String> epics, List<String> resolutions) {
        return new MarketDataSubscribe(epics, resolutions, List.of());
    }

    public MarketDataSubscribe {
        epics = List.copyOf(requireNotEmpty(epics, "epics"));
        resolutions = List.copyOf(requireNotEmpty(resolutions, "resolutions"));
        types = types == null ? List.of() : List.copyOf(types);
    }

    private static List<String> requireNotEmpty(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Subscription %s must not be empty".formatted(fieldName));
        }

        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Subscription %s must not contain blank values".formatted(fieldName));
        }

        return values;
    }
}
