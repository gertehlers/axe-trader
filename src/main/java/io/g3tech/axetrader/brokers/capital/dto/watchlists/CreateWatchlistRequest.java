package io.g3tech.axetrader.brokers.capital.dto.watchlists;

import java.util.Set;

public record CreateWatchlistRequest(
        String name,
        Set<String> epics
) {
}
