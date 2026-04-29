package io.g3tech.axetrader.brokers.capital.dto.watchlists;

public record WatchlistsItem(
        String id,
        String name,
        Boolean editable,
        Boolean deleteable,
        Boolean defaultSystemWatchlist
) {
}
