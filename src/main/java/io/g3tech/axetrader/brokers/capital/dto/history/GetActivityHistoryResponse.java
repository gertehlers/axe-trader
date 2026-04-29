package io.g3tech.axetrader.brokers.capital.dto.history;

import java.util.List;

public record GetActivityHistoryResponse(
        List<ActivitiesItem> activities
) {
}
