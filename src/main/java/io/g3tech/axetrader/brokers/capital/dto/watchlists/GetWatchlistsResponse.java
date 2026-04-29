package io.g3tech.axetrader.brokers.capital.dto.watchlists;

import java.util.List;

public record GetWatchlistsResponse(
        List<WatchlistsItem> watchlists
) {
}
