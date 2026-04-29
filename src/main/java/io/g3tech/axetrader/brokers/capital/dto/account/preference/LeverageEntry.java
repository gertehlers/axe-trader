package io.g3tech.axetrader.brokers.capital.dto.account.preference;

import java.util.List;

public record LeverageEntry(
        List<Integer> available,
        int current
) {
}
