package io.g3tech.axetrader.brokers.capital.dto.clientsentiment;

public record GetClientSentimentResponse(
        String marketId,
        Float longPositionPercentage,
        Float shortPositionPercentage
) {
}
