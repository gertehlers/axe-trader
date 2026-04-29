package io.g3tech.axetrader.brokers.capital.dto.ws.market;

import java.util.List;

public record MarketDataSubscribe(List<String> epics, List<String> resolutions) {
}
