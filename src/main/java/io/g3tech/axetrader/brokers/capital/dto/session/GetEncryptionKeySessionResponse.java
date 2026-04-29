package io.g3tech.axetrader.brokers.capital.dto.session;

public record GetEncryptionKeySessionResponse(
        String encryptionKey,
        Long timeStamp
) {
}
