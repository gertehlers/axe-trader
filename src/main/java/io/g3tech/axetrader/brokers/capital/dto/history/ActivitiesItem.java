package io.g3tech.axetrader.brokers.capital.dto.history;

public record ActivitiesItem(
        String epic,
        String dealId,
        String activityHistoryId,
        String date,
        String time,
        String activity,
        String marketName,
        String period,
        String result,
        String channel,
        String currency,
        String size,
        String level,
        String stop,
        String stopType,
        String limit,
        String actionStatus
) {
}
