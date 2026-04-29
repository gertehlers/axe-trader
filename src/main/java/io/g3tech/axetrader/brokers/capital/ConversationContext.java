package io.g3tech.axetrader.brokers.capital;

public record ConversationContext(
        String apiKey,
        String clientSecurityToken,
        String accountSecurityToken,
        String streamingUrl
) {
}
