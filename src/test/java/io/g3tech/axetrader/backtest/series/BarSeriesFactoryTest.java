package io.g3tech.axetrader.backtest.series;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest
class BarSeriesFactoryTest {

    @Autowired
    private BarSeriesFactory barSeriesFactory;

    @Test
    void buildsAggregatedSeriesFromHistoricalPrices() {
        BarSeries series = barSeriesFactory.build("US500", 10000, 5);

        assertThat(series).isNotNull();
        assertThat(series.getBarCount()).isGreaterThan(0);

        int expectedApprox = 10000 / 5;
        assertThat(series.getBarCount())
                .isBetween((int) (expectedApprox * 0.5), (int) (expectedApprox * 1.5));

        var first = series.getBar(series.getBeginIndex());
        var last = series.getBar(series.getEndIndex());
        assertThat(first.getEndTime()).isBefore(last.getEndTime());
    }

    @Test
    void preservesMidBidAndAskClosesAtMatchingTimestamps() {
        BarSeriesFactory factory = new BarSeriesFactory(null);

        MarketSeries series = factory.fromPricesWithSides("US500", List.of(
                price("2026-01-01T00:01:00Z", 99.0, 101.0),
                price("2026-01-01T00:02:00Z", 109.0, 111.0)), 1);

        assertThat(series.mid().getBarCount()).isEqualTo(2);
        assertThat(series.mid().getBar(0).getEndTime()).isEqualTo(series.bid().getBar(0).getEndTime());
        assertThat(series.mid().getBar(0).getEndTime()).isEqualTo(series.ask().getBar(0).getEndTime());
        assertThat(series.mid().getBar(1).getEndTime()).isEqualTo(series.bid().getBar(1).getEndTime());
        assertThat(series.mid().getBar(1).getEndTime()).isEqualTo(series.ask().getBar(1).getEndTime());
        assertThat(series.mid().getBar(0).getClosePrice().doubleValue()).isEqualTo(100.0);
        assertThat(series.bid().getBar(0).getClosePrice().doubleValue()).isEqualTo(99.0);
        assertThat(series.ask().getBar(0).getClosePrice().doubleValue()).isEqualTo(101.0);
        assertThat(series.mid().getBar(1).getClosePrice().doubleValue()).isEqualTo(110.0);
        assertThat(series.bid().getBar(1).getClosePrice().doubleValue()).isEqualTo(109.0);
        assertThat(series.ask().getBar(1).getClosePrice().doubleValue()).isEqualTo(111.0);
    }

    @Test
    void retainsTheLegacyMidpointSeriesName() {
        BarSeriesFactory factory = new BarSeriesFactory(null);

        BarSeries series = factory.fromPrices("US500", List.of(
                price("2026-01-01T00:01:00Z", 99.0, 101.0)), 1);

        assertThat(series.getName()).isEqualTo("US500_1m");
    }

    @Test
    void rejectsNonPositiveSidePrices() {
        BarSeriesFactory factory = new BarSeriesFactory(null);
        HistoricalPrice invalid = price("2026-01-01T00:01:00Z", 99.0, 101.0);
        invalid.setCloseBid(0.0);

        assertThatIllegalArgumentException().isThrownBy(() ->
                factory.fromPricesWithSides("US500", List.of(invalid), 1));
    }

    @Test
    void rejectsAskBelowBidForAnyOhlcField() {
        BarSeriesFactory factory = new BarSeriesFactory(null);
        HistoricalPrice invalid = price("2026-01-01T00:01:00Z", 99.0, 101.0);
        invalid.setHighAsk(98.0);

        assertThatIllegalArgumentException().isThrownBy(() ->
                factory.fromPricesWithSides("US500", List.of(invalid), 1));
    }

    @Test
    void excludesAnyTimeframeBucketContainingAnExcludedMinute() {
        BarSeriesFactory factory = new BarSeriesFactory(null);

        MarketSeries series = factory.fromPricesWithSides("US500", fiveMinutes(), 5,
                Set.of(Instant.parse("2024-01-01T00:03:00Z")));

        assertThat(series.mid().getBarCount()).isZero();
    }

    @Test
    void omitsFifteenMinuteBucketWhenOneMinuteIsMissing() {
        BarSeriesFactory factory = new BarSeriesFactory(null);

        MarketSeries series = factory.fromPricesWithSides("US500", fourteenMinutes(), 15, Set.of());

        assertThat(series.mid().getBarCount()).isZero();
    }

    @Test
    void keepsCompleteBucketsOnBothSidesOfAnExcludedUtcBoundaryBucket() {
        BarSeriesFactory factory = new BarSeriesFactory(null);
        List<HistoricalPrice> prices = new ArrayList<>();
        for (int minuteOffset = 0; minuteOffset < 15; minuteOffset++) {
            Instant timestamp = Instant.parse("2023-12-31T23:56:00Z").plusSeconds((long) minuteOffset * 60);
            prices.add(price(timestamp.toString(), 99.0, 101.0));
        }

        MarketSeries series = factory.fromPricesWithSides("US500", prices, 5,
                Set.of(Instant.parse("2024-01-01T00:03:00Z")));

        assertThat(series.mid().getBarCount()).isEqualTo(2);
        assertThat(series.mid().getBar(0).getEndTime()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(series.mid().getBar(1).getEndTime()).isEqualTo(Instant.parse("2024-01-01T00:10:00Z"));
        assertThat(series.bid().getBar(0).getEndTime()).isEqualTo(series.mid().getBar(0).getEndTime());
        assertThat(series.ask().getBar(1).getEndTime()).isEqualTo(series.mid().getBar(1).getEndTime());
    }

    private static HistoricalPrice price(String timestamp, double bid, double ask) {
        HistoricalPrice price = new HistoricalPrice();
        price.setSnapshotTimeUtc(Instant.parse(timestamp));
        price.setOpenBid(bid);
        price.setHighBid(bid);
        price.setLowBid(bid);
        price.setCloseBid(bid);
        price.setOpenAsk(ask);
        price.setHighAsk(ask);
        price.setLowAsk(ask);
        price.setCloseAsk(ask);
        price.setLastTradedVolume(1);
        return price;
    }

    private static List<HistoricalPrice> fiveMinutes() {
        return List.of(
                price("2024-01-01T00:01:00Z", 99.0, 101.0),
                price("2024-01-01T00:02:00Z", 99.0, 101.0),
                price("2024-01-01T00:03:00Z", 99.0, 101.0),
                price("2024-01-01T00:04:00Z", 99.0, 101.0),
                price("2024-01-01T00:05:00Z", 99.0, 101.0));
    }

    private static List<HistoricalPrice> fourteenMinutes() {
        List<HistoricalPrice> prices = new ArrayList<>();
        for (int minute = 1; minute <= 14; minute++) {
            prices.add(price("2024-01-01T00:%02d:00Z".formatted(minute), 99.0, 101.0));
        }
        return prices;
    }
}
