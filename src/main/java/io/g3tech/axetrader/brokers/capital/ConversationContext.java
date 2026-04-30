package io.g3tech.axetrader.brokers.capital;

public record ConversationContext(
        String clientSecurityToken,
        String accountSecurityToken,
        String streamingUrl
) {

    public ConversationContext {
        requirePresent(clientSecurityToken, "clientSecurityToken");
        requirePresent(accountSecurityToken, "accountSecurityToken");
        requirePresent(streamingUrl, "streamingUrl");
    }

    private static void requirePresent(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required conversation context field: %s".formatted(fieldName));
        }
    }
}
