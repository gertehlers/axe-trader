package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.brokers.capital.dto.prices.ClosePrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesRequest;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.HighPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.LowPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.OpenPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalHistoricalPriceMapperTests {

    private final CapitalHistoricalPriceMapper mapper = Mappers.getMapper(CapitalHistoricalPriceMapper.class);

    @Test
    void mapsCapitalPricesToHistoricalPrices() {
        var request = new GetPricesRequest("US500", "MINUTE_5", Instant.parse("2026-04-30T10:00:00Z"), null, 1000);
        var item = new PricesItem(
                "2026-04-30T10:00:00",
                "2026-04-30T10:00:00",
                new OpenPrice(new BigDecimal("100.1"), new BigDecimal("100.3"), null),
                new ClosePrice(new BigDecimal("101.1"), new BigDecimal("101.3"), null),
                new HighPrice(new BigDecimal("102.1"), new BigDecimal("102.3"), null),
                new LowPrice(new BigDecimal("99.1"), new BigDecimal("99.3"), null),
                1_250L
        );
        var response = new GetPricesResponse(List.of(item), null, null);

        var historicalPrices = mapper.toHistoricalPrices(response, request);

        assertThat(historicalPrices).hasSize(1);
        var historicalPrice = historicalPrices.getFirst();
        assertThat(historicalPrice.getId()).isNull();
        assertThat(historicalPrice.getEpic()).isEqualTo("US500");
        assertThat(historicalPrice.getResolution()).isEqualTo("MINUTE_5");
        assertThat(historicalPrice.getSnapshotTimeUtc()).isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
        assertThat(historicalPrice.getOpenBid()).isEqualTo(100.1);
        assertThat(historicalPrice.getOpenAsk()).isEqualTo(100.3);
        assertThat(historicalPrice.getHighBid()).isEqualTo(102.1);
        assertThat(historicalPrice.getHighAsk()).isEqualTo(102.3);
        assertThat(historicalPrice.getLowBid()).isEqualTo(99.1);
        assertThat(historicalPrice.getLowAsk()).isEqualTo(99.3);
        assertThat(historicalPrice.getCloseBid()).isEqualTo(101.1);
        assertThat(historicalPrice.getCloseAsk()).isEqualTo(101.3);
        assertThat(historicalPrice.getLastTradedVolume()).isEqualTo(1_250);
        assertThat(historicalPrice.getSource()).isEqualTo("capital");
        assertThat(historicalPrice.getIngestionTimeUtc()).isNotNull();
    }

    @Test
    void mapsHistoricalPriceToBacktestPrice() {
        var historicalPrice = new HistoricalPrice();
        historicalPrice.setEpic("US500");
        historicalPrice.setResolution("MINUTE_5");
        historicalPrice.setSnapshotTimeUtc(Instant.parse("2026-04-30T10:00:00Z"));
        historicalPrice.setOpenBid(100.1);
        historicalPrice.setOpenAsk(100.3);
        historicalPrice.setHighBid(102.1);
        historicalPrice.setHighAsk(102.3);
        historicalPrice.setLowBid(99.1);
        historicalPrice.setLowAsk(99.3);
        historicalPrice.setCloseBid(101.1);
        historicalPrice.setCloseAsk(101.3);
        historicalPrice.setLastTradedVolume(1_250);
        historicalPrice.setSource("capital");
        historicalPrice.setIngestionTimeUtc(Instant.parse("2026-04-30T10:01:00Z"));

        var backtestPrice = mapper.toBacktestPrice(historicalPrice);

        assertThat(backtestPrice.epic()).isEqualTo("US500");
        assertThat(backtestPrice.resolution()).isEqualTo("MINUTE_5");
        assertThat(backtestPrice.snapshotTimeUtc()).isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
        assertThat(backtestPrice.openBid()).isEqualTo(100.1);
        assertThat(backtestPrice.openAsk()).isEqualTo(100.3);
        assertThat(backtestPrice.highBid()).isEqualTo(102.1);
        assertThat(backtestPrice.highAsk()).isEqualTo(102.3);
        assertThat(backtestPrice.lowBid()).isEqualTo(99.1);
        assertThat(backtestPrice.lowAsk()).isEqualTo(99.3);
        assertThat(backtestPrice.closeBid()).isEqualTo(101.1);
        assertThat(backtestPrice.closeAsk()).isEqualTo(101.3);
        assertThat(backtestPrice.lastTradedVolume()).isEqualTo(1_250);
    }
}
