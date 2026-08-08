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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
        return fromPricesWithSides(epic, prices, timeframeMinutes, excludedMinutes, 0);
    }

    /**
     * @param maxMissingMinutesPerBucket how many absent minutes a timeframe bucket may still be
     *     built from. Zero — the default everywhere else — keeps a bucket only when every minute is
     *     present. Discovery raises it because real minute history has scattered absent minutes, and
     *     dropping a whole bucket for one of them punches holes that the extractor's contiguity
     *     check then treats as fatal.
     */
    public MarketSeries fromPricesWithSides(String epic, List<HistoricalPrice> prices, int timeframeMinutes,
                                            Set<Instant> excludedMinutes, int maxMissingMinutesPerBucket) {
        for (HistoricalPrice price : prices) {
            validate(price);
        }

        var buckets = usableBuckets(prices, timeframeMinutes, excludedMinutes, maxMissingMinutesPerBucket);
        BarSeries mid = aggregate(epic, buckets, timeframeMinutes, PriceSide.MID);
        BarSeries bid = aggregate(epic, buckets, timeframeMinutes, PriceSide.BID);
        BarSeries ask = aggregate(epic, buckets, timeframeMinutes, PriceSide.ASK);
        verifyMatchingEndTimes(mid, bid, ask);

        int retainedMinutes = buckets.values().stream().mapToInt(List::size).sum();
        logger.info("Built market series {}: {} 1m bars aggregated to {} {}m bars",
                epic, retainedMinutes, mid.getBarCount(), timeframeMinutes);

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

    private static java.util.NavigableMap<Instant, List<HistoricalPrice>> usableBuckets(
            List<HistoricalPrice> prices, int timeframeMinutes, Set<Instant> excludedMinutes,
            int maxMissingMinutesPerBucket) {
        if (timeframeMinutes <= 0) {
            throw new IllegalArgumentException("timeframeMinutes must be positive");
        }
        if (maxMissingMinutesPerBucket < 0 || maxMissingMinutesPerBucket >= timeframeMinutes) {
            throw new IllegalArgumentException(
                    "maxMissingMinutesPerBucket must be between 0 and timeframeMinutes - 1");
        }

        Duration timeframe = Duration.ofMinutes(timeframeMinutes);
        Set<Instant> exclusions = Set.copyOf(excludedMinutes);
        var pricesByBucket = new java.util.TreeMap<Instant, List<HistoricalPrice>>();
        for (HistoricalPrice price : prices) {
            pricesByBucket.computeIfAbsent(bucketEnd(price.getSnapshotTimeUtc(), timeframe), ignored -> new ArrayList<>())
                    .add(price);
        }

        var usable = new java.util.TreeMap<Instant, List<HistoricalPrice>>();
        for (var entry : pricesByBucket.entrySet()) {
            if (isUsable(entry.getValue(), timeframeMinutes, exclusions, maxMissingMinutesPerBucket)) {
                List<HistoricalPrice> ordered = new ArrayList<>(entry.getValue());
                ordered.sort(Comparator.comparing(HistoricalPrice::getSnapshotTimeUtc));
                usable.put(entry.getKey(), ordered);
            }
        }
        return usable;
    }

    private static Instant bucketEnd(Instant minuteEnd, Duration timeframe) {
        long timeframeSeconds = timeframe.toSeconds();
        long epochSeconds = minuteEnd.getEpochSecond();
        long bucketEndSeconds = Math.floorDiv(epochSeconds - 1, timeframeSeconds) * timeframeSeconds
                + timeframeSeconds;
        return Instant.ofEpochSecond(bucketEndSeconds);
    }

    /**
     * An excluded minute always disqualifies the bucket: that minute's data failed the import
     * audit, so the bar would be built on values known to be wrong. A merely absent minute is
     * different — nothing is wrong with the data that is there — so up to
     * {@code maxMissingMinutesPerBucket} of those are tolerated.
     */
    private static boolean isUsable(List<HistoricalPrice> prices, int timeframeMinutes,
                                    Set<Instant> excludedMinutes, int maxMissingMinutesPerBucket) {
        Set<Instant> presentMinutes = new HashSet<>();
        for (HistoricalPrice price : prices) {
            Instant minute = price.getSnapshotTimeUtc();
            if (excludedMinutes.contains(minute)) {
                return false;
            }
            presentMinutes.add(minute);
        }
        return presentMinutes.size() >= timeframeMinutes - maxMissingMinutesPerBucket;
    }

    /**
     * Builds one bar per wall-clock bucket.
     *
     * <p>This deliberately does not use ta4j's {@code DurationBarAggregator}, which groups by
     * accumulated duration: a bucket built from four of five minutes would fall one minute short
     * and pull in the next bucket's first minute to make up the difference, silently shifting every
     * subsequent bar off the timeframe grid.
     */
    private static BarSeries aggregate(String epic, java.util.NavigableMap<Instant, List<HistoricalPrice>> buckets,
                                       int timeframeMinutes, PriceSide side) {
        Duration timeframe = Duration.ofMinutes(timeframeMinutes);
        BarSeries series = new BaseBarSeriesBuilder().withName(seriesName(epic, side, timeframeMinutes)).build();
        for (var entry : buckets.entrySet()) {
            List<HistoricalPrice> minutes = entry.getValue();
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            long volume = 0;
            for (HistoricalPrice price : minutes) {
                high = Math.max(high, side.high(price));
                low = Math.min(low, side.low(price));
                volume += price.getLastTradedVolume();
            }
            series.barBuilder()
                    .timePeriod(timeframe)
                    .endTime(entry.getKey())
                    .openPrice(side.open(minutes.getFirst()))
                    .highPrice(high)
                    .lowPrice(low)
                    .closePrice(side.close(minutes.getLast()))
                    .volume(volume)
                    .add();
        }
        return series;
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
