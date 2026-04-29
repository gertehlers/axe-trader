package io.g3tech.axetrader.brokers.capital.dto.account.preference;

import java.util.Map;

public record GetPreferencesResponse(
        boolean hedgingMode,
        Map<LeverageGroup, LeverageEntry> leverages
) {
}
