package io.g3tech.axetrader.brokers.capital.dto.prices;

import java.time.Instant;

public record GetPricesRequest(
        String epic,
        String resolution,
        Instant from,
        Instant to,
        Integer max
) {

    public GetPricesRequest {
        if (epic == null || epic.isBlank()) {
            throw new IllegalArgumentException("epic must be configured");
        }

        if (resolution == null || resolution.isBlank()) {
            throw new IllegalArgumentException("resolution must be configured");
        }

        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }

        if (max != null && max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
    }
}
