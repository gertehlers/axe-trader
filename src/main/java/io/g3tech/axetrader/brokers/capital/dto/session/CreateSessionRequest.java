package io.g3tech.axetrader.brokers.capital.dto.session;

public record CreateSessionRequest(
        String identifier,
        String password,
        Boolean encryptedPassword
) {
}
