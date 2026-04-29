package io.g3tech.axetrader.brokers.capital.dto.prices;

public record PricesItem(
        String snapshotTime,
        String snapshotTimeUTC,
        OpenPrice openPrice,
        ClosePrice closePrice,
        HighPrice highPrice,
        LowPrice lowPrice,
        Long lastTradedVolume
) {
}
