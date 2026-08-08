package io.g3tech.axetrader.backtest.discovery;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryInputDigestTest {

    @Test
    void producesTheSameDigestForEqualRowLists() {
        assertThat(DiscoveryInputDigest.of(rows(100.0))).isEqualTo(DiscoveryInputDigest.of(rows(100.0)));
    }

    @Test
    void producesADifferentDigestWhenASingleOhlcValueDiffers() {
        assertThat(DiscoveryInputDigest.of(rows(100.0))).isNotEqualTo(DiscoveryInputDigest.of(rows(100.5)));
    }

    @Test
    void producesADifferentDigestWhenARowIsMissing() {
        List<HistoricalPrice> shortened = rows(100.0).subList(0, 2);

        assertThat(DiscoveryInputDigest.of(rows(100.0))).isNotEqualTo(DiscoveryInputDigest.of(shortened));
    }

    @Test
    void producesADifferentDigestWhenOnlyTheTimestampsShift() {
        List<HistoricalPrice> shifted = rows(100.0);
        shifted.get(1).setSnapshotTimeUtc(Instant.parse("2024-01-01T00:07:00Z"));

        assertThat(DiscoveryInputDigest.of(rows(100.0))).isNotEqualTo(DiscoveryInputDigest.of(shifted));
    }

    @Test
    void refusesAnEmptyWindowBecauseThereIsNothingToIdentify() {
        assertThatThrownBy(() -> DiscoveryInputDigest.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rows");
    }

    private static List<HistoricalPrice> rows(double firstClose) {
        return List.of(
                price(Instant.parse("2024-01-01T00:01:00Z"), firstClose),
                price(Instant.parse("2024-01-01T00:02:00Z"), 101.0),
                price(Instant.parse("2024-01-01T00:03:00Z"), 102.0));
    }

    private static HistoricalPrice price(Instant time, double close) {
        HistoricalPrice price = new HistoricalPrice();
        price.setSnapshotTimeUtc(time);
        price.setOpenBid(close - 1);
        price.setOpenAsk(close - 0.9);
        price.setHighBid(close + 1);
        price.setHighAsk(close + 1.1);
        price.setLowBid(close - 2);
        price.setLowAsk(close - 1.9);
        price.setCloseBid(close);
        price.setCloseAsk(close + 0.1);
        price.setLastTradedVolume(10);
        return price;
    }
}
