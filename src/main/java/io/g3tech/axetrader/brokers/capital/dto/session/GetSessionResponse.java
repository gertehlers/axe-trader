package io.g3tech.axetrader.brokers.capital.dto.session;

public record GetSessionResponse(
        String accountId,
        String clientId,
        String locale,
        String currency,
        String streamEndpoint,
        int timezoneOffset
) {
}
