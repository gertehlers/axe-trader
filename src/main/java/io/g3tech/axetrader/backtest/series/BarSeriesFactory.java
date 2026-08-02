package io.g3tech.axetrader.backtest.series;

import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import io.g3tech.axetrader.strategy.backtest.repositories.PriceExclusionRepository;
import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class BarSeriesFactory {

    private static final Logger logger = LoggerFactory.getLogger(BarSeriesFactory.class);
    private static final String MINUTE_RESOLUTION = "MINUTE";

    private final HistoricalPriceRepository repository;
    private final PriceExclusionRepository priceExclusionRepository;

    public BarSeriesFactory(HistoricalPriceRepository repository) {
        this(repository, null);
    }

    @Autowired
    public BarSeriesFactory(HistoricalPriceRepository repository, PriceExclusionRepository priceExclusionRepository) {
        this.repository = repository;
        this.priceExclusionRepository = priceExclusionRepository;
    }

    public BarSeries build(String epic, int limit, int timeframeMinutes) {
        List<HistoricalPrice> prices = repository.findByEpic(
                epic,
                Sort.by(Sort.Direction.ASC, "snapshotTimeUtc"),
                Limit.of(limit)
        );
        return fromPricesWithSides(epic, prices, timeframeMinutes, excludedMinutes(epic, prices)).mid();
    }

    /**
     * Builds an aggregated series from an already-loaded (ascending) list of 1m prices.
     * Lets callers control the window themselves (e.g. in-sample vs out-of-sample splits)
     * instead of being bound to the epic+limit query above.
     */
    public BarSeries fromPrices(String epic, List<HistoricalPrice> prices, int timeframeMinutes) {
        return fromPricesWithSides(epic, prices, timeframeMinutes).mid();
    }

    public MarketSeries fromPricesWithSides(String epic, List<HistoricalPrice> prices, int timeframeMinutes) {
        return fromPricesWithSides(epic, prices, timeframeMinutes, Set.of());
    }

    public MarketSeries fromPricesWithSides(String epic, List<HistoricalPrice> prices, int timeframeMinutes,
                                            Set<Instant> excludedMinutes) {
        for (HistoricalPrice price : prices) {
            validate(price);
        }

        List<HistoricalPrice> completePrices = completeBuckets(prices, timeframeMinutes, excludedMinutes);
        BarSeries mid = aggregate(epic, completePrices, timeframeMinutes, PriceSide.MID);
        BarSeries bid = aggregate(epic, completePrices, timeframeMinutes, PriceSide.BID);
        BarSeries ask = aggregate(epic, completePrices, timeframeMinutes, PriceSide.ASK);
        verifyMatchingEndTimes(mid, bid, ask);

        logger.info("Built market series {}: {} 1m bars aggregated to {} {}m bars",
                epic, completePrices.size(), mid.getBarCount(), timeframeMinutes);

        return new MarketSeries(mid, bid, ask);
    }

    private Set<Instant> excludedMinutes(String epic, List<HistoricalPrice> prices) {
        if (priceExclusionRepository == null || prices.isEmpty()) {
            return Set.of();
        }
        if (priceExclusionRepository.priceExclusionLedgerTableCount() == 0) {
            return Set.of();
        }

        Instant from = prices.stream().map(HistoricalPrice::getSnapshotTimeUtc).min(Instant::compareTo).orElseThrow();
        Instant to = prices.stream().map(HistoricalPrice::getSnapshotTimeUtc).max(Instant::compareTo)
                .orElseThrow().plus(Duration.ofMinutes(1));
        String resolution = prices.stream().map(HistoricalPrice::getResolution)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(MINUTE_RESOLUTION);
        return priceExclusionRepository.findDistinctSnapshotTimes(epic, resolution, from.toString(), to.toString())
                .stream()
                .map(Instant::parse)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<HistoricalPrice> completeBuckets(List<HistoricalPrice> prices, int timeframeMinutes,
                                                          Set<Instant> excludedMinutes) {
        if (timeframeMinutes <= 0) {
            throw new IllegalArgumentException("timeframeMinutes must be positive");
        }

        Duration timeframe = Duration.ofMinutes(timeframeMinutes);
        Set<Instant> exclusions = Set.copyOf(excludedMinutes);
        var pricesByBucket = new java.util.TreeMap<Instant, List<HistoricalPrice>>();
        for (HistoricalPrice price : prices) {
            pricesByBucket.computeIfAbsent(bucketEnd(price.getSnapshotTimeUtc(), timeframe), ignored -> new ArrayList<>())
                    .add(price);
        }

        List<HistoricalPrice> complete = new ArrayList<>();
        for (var entry : pricesByBucket.entrySet()) {
            if (isComplete(entry.getKey(), entry.getValue(), timeframeMinutes, exclusions)) {
                complete.addAll(entry.getValue());
            }
        }
        return complete;
    }

    private static Instant bucketEnd(Instant minuteEnd, Duration timeframe) {
        long timeframeSeconds = timeframe.toSeconds();
        long epochSeconds = minuteEnd.getEpochSecond();
        long bucketEndSeconds = Math.floorDiv(epochSeconds - 1, timeframeSeconds) * timeframeSeconds
                + timeframeSeconds;
        return Instant.ofEpochSecond(bucketEndSeconds);
    }

    private static boolean isComplete(Instant bucketEnd, List<HistoricalPrice> prices, int timeframeMinutes,
                                      Set<Instant> excludedMinutes) {
        Set<Instant> presentMinutes = new HashSet<>();
        for (HistoricalPrice price : prices) {
            Instant minute = price.getSnapshotTimeUtc();
            if (excludedMinutes.contains(minute)) {
                return false;
            }
            presentMinutes.add(minute);
        }
        if (presentMinutes.size() != timeframeMinutes) {
            return false;
        }
        for (int minuteOffset = 0; minuteOffset < timeframeMinutes; minuteOffset++) {
            if (!presentMinutes.contains(bucketEnd.minus(Duration.ofMinutes(minuteOffset)))) {
                return false;
            }
        }
        return true;
    }

    private static BarSeries aggregate(String epic, List<HistoricalPrice> prices, int timeframeMinutes, PriceSide side) {
        BarSeries oneMinute = new BaseBarSeriesBuilder().withName(seriesName(epic, side, 1)).build();
        for (HistoricalPrice price : prices) {
            oneMinute.barBuilder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(price.getSnapshotTimeUtc())
                    .openPrice(side.open(price))
                    .highPrice(side.high(price))
                    .lowPrice(side.low(price))
                    .closePrice(side.close(price))
                    .volume(price.getLastTradedVolume())
                    .add();
        }

        BaseBarSeriesAggregator aggregator = new BaseBarSeriesAggregator(
                new DurationBarAggregator(Duration.ofMinutes(timeframeMinutes), true));
        return aggregator.aggregate(oneMinute, seriesName(epic, side, timeframeMinutes));
    }

    private static String seriesName(String epic, PriceSide side, int timeframeMinutes) {
        String sidePrefix = side == PriceSide.MID ? "" : "_" + side.name().toLowerCase();
        return epic + sidePrefix + "_" + timeframeMinutes + "m";
    }

    private static void validate(HistoricalPrice price) {
        validatePositive(price.getOpenBid(), "open bid");
        validatePositive(price.getHighBid(), "high bid");
        validatePositive(price.getLowBid(), "low bid");
        validatePositive(price.getCloseBid(), "close bid");
        validatePositive(price.getOpenAsk(), "open ask");
        validatePositive(price.getHighAsk(), "high ask");
        validatePositive(price.getLowAsk(), "low ask");
        validatePositive(price.getCloseAsk(), "close ask");

        validateAskNotBelowBid(price.getOpenBid(), price.getOpenAsk(), "open");
        validateAskNotBelowBid(price.getHighBid(), price.getHighAsk(), "high");
        validateAskNotBelowBid(price.getLowBid(), price.getLowAsk(), "low");
        validateAskNotBelowBid(price.getCloseBid(), price.getCloseAsk(), "close");
    }

    private static void validatePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void validateAskNotBelowBid(double bid, double ask, String field) {
        if (ask < bid) {
            throw new IllegalArgumentException(field + " ask must be greater than or equal to bid");
        }
    }

    private static void verifyMatchingEndTimes(BarSeries mid, BarSeries bid, BarSeries ask) {
        if (mid.getBarCount() != bid.getBarCount() || mid.getBarCount() != ask.getBarCount()) {
            throw new IllegalArgumentException("mid, bid and ask series must have equal bar counts");
        }
        for (int index = 0; index < mid.getBarCount(); index++) {
            if (!mid.getBar(index).getEndTime().equals(bid.getBar(index).getEndTime())
                    || !mid.getBar(index).getEndTime().equals(ask.getBar(index).getEndTime())) {
                throw new IllegalArgumentException("mid, bid and ask series must have matching bar end times");
            }
        }
    }

    private enum PriceSide {
        MID {
            @Override
            double open(HistoricalPrice price) {
                return mid(price.getOpenBid(), price.getOpenAsk());
            }

            @Override
            double high(HistoricalPrice price) {
                return mid(price.getHighBid(), price.getHighAsk());
            }

            @Override
            double low(HistoricalPrice price) {
                return mid(price.getLowBid(), price.getLowAsk());
            }

            @Override
            double close(HistoricalPrice price) {
                return mid(price.getCloseBid(), price.getCloseAsk());
            }
        },
        BID {
            @Override
            double open(HistoricalPrice price) {
                return price.getOpenBid();
            }

            @Override
            double high(HistoricalPrice price) {
                return price.getHighBid();
            }

            @Override
            double low(HistoricalPrice price) {
                return price.getLowBid();
            }

            @Override
            double close(HistoricalPrice price) {
                return price.getCloseBid();
            }
        },
        ASK {
            @Override
            double open(HistoricalPrice price) {
                return price.getOpenAsk();
            }

            @Override
            double high(HistoricalPrice price) {
                return price.getHighAsk();
            }

            @Override
            double low(HistoricalPrice price) {
                return price.getLowAsk();
            }

            @Override
            double close(HistoricalPrice price) {
                return price.getCloseAsk();
            }
        };

        abstract double open(HistoricalPrice price);

        abstract double high(HistoricalPrice price);

        abstract double low(HistoricalPrice price);

        abstract double close(HistoricalPrice price);

        private static double mid(double bid, double ask) {
            return (bid + ask) / 2;
        }
    }
}
