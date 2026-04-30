package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import io.g3tech.axetrader.strategy.Candle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class CapitalPriceCandleMapper {

    public List<Candle> toCandles(GetPricesResponse response) {
        if (response == null || response.prices() == null) {
            return List.of();
        }

        return response.prices().stream()
                .filter(Objects::nonNull)
                .map(this::toCandle)
                .sorted(Comparator.comparing(Candle::openedAt))
                .toList();
    }

    private Candle toCandle(PricesItem item) {
        return new Candle(
                timestamp(item),
                mid(item.openPrice().bid(), item.openPrice().ask(), item.openPrice().lastTraded()),
                mid(item.highPrice().bid(), item.highPrice().ask(), item.highPrice().lastTraded()),
                mid(item.lowPrice().bid(), item.lowPrice().ask(), item.lowPrice().lastTraded()),
                mid(item.closePrice().bid(), item.closePrice().ask(), item.closePrice().lastTraded()),
                item.lastTradedVolume()
        );
    }

    private Instant timestamp(PricesItem item) {
        if (item.snapshotTimeUTC() != null && !item.snapshotTimeUTC().isBlank()) {
            return parseCapitalTimestamp(item.snapshotTimeUTC());
        }

        if (item.snapshotTime() != null && !item.snapshotTime().isBlank()) {
            return parseCapitalTimestamp(item.snapshotTime());
        }

        throw new IllegalArgumentException("Price item is missing snapshot timestamp");
    }

    private Instant parseCapitalTimestamp(String value) {
        var normalized = value.replace('/', '-').replace(' ', 'T');

        if (normalized.endsWith("Z")) {
            return Instant.parse(normalized);
        }

        return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
    }

    private BigDecimal mid(BigDecimal bid, BigDecimal ask, BigDecimal lastTraded) {
        if (bid != null && ask != null) {
            return bid.add(ask).divide(BigDecimal.valueOf(2));
        }

        if (lastTraded != null) {
            return lastTraded;
        }

        if (bid != null) {
            return bid;
        }

        if (ask != null) {
            return ask;
        }

        throw new IllegalArgumentException("Price point is missing bid, ask and last traded values");
    }
}
