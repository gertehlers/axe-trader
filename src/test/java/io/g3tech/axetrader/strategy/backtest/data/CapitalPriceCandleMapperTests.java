package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.brokers.capital.dto.prices.ClosePrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.GetPricesResponse;
import io.g3tech.axetrader.brokers.capital.dto.prices.HighPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.LowPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.OpenPrice;
import io.g3tech.axetrader.brokers.capital.dto.prices.PricesItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalPriceCandleMapperTests {

    private final CapitalPriceCandleMapper mapper = new CapitalPriceCandleMapper();

    @Test
    void mapsCapitalPricesToSortedMidPriceCandles() {
        var later = price("2026-04-30T10:05:00", "101", "103", "100", "102");
        var earlier = price("2026-04-30T10:00:00", "100", "102", "99", "101");
        var response = new GetPricesResponse(List.of(later, earlier), null, null);

        var candles = mapper.toCandles(response);

        assertThat(candles).hasSize(2);
        assertThat(candles.getFirst().openedAt()).isEqualTo(Instant.parse("2026-04-30T10:00:00Z"));
        assertThat(candles.getFirst().open()).isEqualByComparingTo("100.5");
        assertThat(candles.getFirst().close()).isEqualByComparingTo("101.5");
    }

    private PricesItem price(String timestamp, String open, String high, String low, String close) {
        return new PricesItem(
                timestamp,
                timestamp,
                new OpenPrice(new BigDecimal(open), new BigDecimal(open).add(BigDecimal.ONE), null),
                new ClosePrice(new BigDecimal(close), new BigDecimal(close).add(BigDecimal.ONE), null),
                new HighPrice(new BigDecimal(high), new BigDecimal(high).add(BigDecimal.ONE), null),
                new LowPrice(new BigDecimal(low), new BigDecimal(low).add(BigDecimal.ONE), null),
                1_000L
        );
    }
}
