package io.g3tech.axetrader.brokers.capital.dto.watchlists;

public record CreateWatchlistResponse(
        String watchlistId,
        Status status
) {
}
