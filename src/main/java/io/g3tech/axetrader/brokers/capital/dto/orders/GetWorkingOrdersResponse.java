package io.g3tech.axetrader.brokers.capital.dto.orders;

import java.util.List;

public record GetWorkingOrdersResponse(
        List<WorkingOrdersItem> workingOrders
) {
}
