package io.g3tech.axetrader.brokers.capital.dto.account.preference;

import java.util.Map;

public record UpdatePreferencesRequest(
        boolean hedgingMode,
        Map<LeverageGroup, Integer> leverages
) {
}
