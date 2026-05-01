package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import io.g3tech.axetrader.strategy.backtest.BacktestPrice;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Mapper(componentModel = "spring")
public interface CapitalHistoricalPriceMapper {

    default List<HistoricalPrice> toHistoricalPrices(GetPricesResponse response, GetPricesRequest request) {
        if (response == null || response.prices() == null) {
            return List.of();
        }

        return response.prices().stream()
                .filter(Objects::nonNull)
                .map(item -> toHistoricalPrice(item, request))
                .toList();
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "epic", source = "request.epic")
    @Mapping(target = "resolution", source = "request.resolution")
    @Mapping(target = "snapshotTimeUtc", source = "item", qualifiedByName = "snapshotTimeUtc")
    @Mapping(target = "openBid", source = "item.openPrice.bid")
    @Mapping(target = "openAsk", source = "item.openPrice.ask")
    @Mapping(target = "highBid", source = "item.highPrice.bid")
    @Mapping(target = "highAsk", source = "item.highPrice.ask")
    @Mapping(target = "lowBid", source = "item.lowPrice.bid")
    @Mapping(target = "lowAsk", source = "item.lowPrice.ask")
    @Mapping(target = "closeBid", source = "item.closePrice.bid")
    @Mapping(target = "closeAsk", source = "item.closePrice.ask")
    @Mapping(target = "lastTradedVolume", source = "item.lastTradedVolume")
    @Mapping(target = "source", constant = "capital")
    @Mapping(target = "ingestionTimeUtc", expression = "java(java.time.Instant.now())")
    HistoricalPrice toHistoricalPrice(PricesItem item, GetPricesRequest request);

    BacktestPrice toBacktestPrice(HistoricalPrice historicalPrice);

    List<BacktestPrice> toBacktestPrices(Iterable<HistoricalPrice> historicalPrices);

    default double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    default int toInt(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }

    @Named("snapshotTimeUtc")
    default Instant snapshotTimeUtc(PricesItem item) {
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
}
