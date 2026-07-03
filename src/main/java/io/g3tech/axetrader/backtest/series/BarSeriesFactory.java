package io.g3tech.axetrader.backtest.series;

import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.aggregator.BaseBarSeriesAggregator;
import org.ta4j.core.aggregator.DurationBarAggregator;

import java.time.Duration;
import java.util.List;

@Component
public class BarSeriesFactory {

    private static final Logger logger = LoggerFactory.getLogger(BarSeriesFactory.class);

    private final HistoricalPriceRepository repository;

    public BarSeriesFactory(HistoricalPriceRepository repository) {
        this.repository = repository;
    }

    public BarSeries build(String epic, int limit, int timeframeMinutes) {
        List<HistoricalPrice> prices = repository.findByEpic(
                epic,
                Sort.by(Sort.Direction.ASC, "snapshotTimeUtc"),
                Limit.of(limit)
        );
        return fromPrices(epic, prices, timeframeMinutes);
    }

    /**
     * Builds an aggregated series from an already-loaded (ascending) list of 1m prices.
     * Lets callers control the window themselves (e.g. in-sample vs out-of-sample splits)
     * instead of being bound to the epic+limit query above.
     */
    public BarSeries fromPrices(String epic, List<HistoricalPrice> prices, int timeframeMinutes) {
        BarSeries oneMinute = new BaseBarSeriesBuilder().withName(epic + "_1m").build();
        for (HistoricalPrice price : prices) {
            oneMinute.barBuilder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(price.getSnapshotTimeUtc())
                    .openPrice(mid(price.getOpenBid(), price.getOpenAsk()))
                    .highPrice(mid(price.getHighBid(), price.getHighAsk()))
                    .lowPrice(mid(price.getLowBid(), price.getLowAsk()))
                    .closePrice(mid(price.getCloseBid(), price.getCloseAsk()))
                    .volume(price.getLastTradedVolume())
                    .add();
        }

        BaseBarSeriesAggregator aggregator = new BaseBarSeriesAggregator(
                new DurationBarAggregator(Duration.ofMinutes(timeframeMinutes), true));
        BarSeries aggregated = aggregator.aggregate(oneMinute, epic + "_" + timeframeMinutes + "m");

        logger.info("Built bar series {}: {} 1m bars aggregated to {} {}m bars",
                epic, oneMinute.getBarCount(), aggregated.getBarCount(), timeframeMinutes);

        return aggregated;
    }

    private static double mid(double bid, double ask) {
        return (bid + ask) / 2;
    }
}
