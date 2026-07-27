package io.g3tech.axetrader.backtest.discovery.session;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class HistoricalSessionCalendar implements TradingSessionCalendar {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final Set<Instant> availableBarTimes;
    private final NavigableMap<Instant, ObservedGap> gapsByFinalBar;

    private HistoricalSessionCalendar(Set<Instant> availableBarTimes, NavigableMap<Instant, ObservedGap> gapsByFinalBar) {
        this.availableBarTimes = availableBarTimes;
        this.gapsByFinalBar = gapsByFinalBar;
    }

    public static HistoricalSessionCalendar fit(List<Instant> oneMinuteBarTimes, int minimumOccurrences) {
        Objects.requireNonNull(oneMinuteBarTimes, "oneMinuteBarTimes");
        if (minimumOccurrences < 1) {
            throw new IllegalArgumentException("minimumOccurrences must be positive");
        }

        List<Instant> times = oneMinuteBarTimes.stream()
                .peek(time -> Objects.requireNonNull(time, "oneMinuteBarTimes cannot contain null"))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<ObservedGap> gaps = gapsIn(times);
        Map<GapSignature, Integer> occurrences = new HashMap<>();
        for (ObservedGap gap : gaps) {
            occurrences.merge(gap.signature(), 1, Integer::sum);
        }

        NavigableMap<Instant, ObservedGap> gapsByFinalBar = new TreeMap<>();
        for (ObservedGap gap : gaps) {
            if (occurrences.get(gap.signature()) >= minimumOccurrences) {
                gapsByFinalBar.put(gap.boundary().finalExecutableBar(), gap);
            } else {
                gapsByFinalBar.put(gap.boundary().finalExecutableBar(), gap.asUnknown());
            }
        }
        return new HistoricalSessionCalendar(Set.copyOf(times), new TreeMap<>(gapsByFinalBar));
    }

    @Override
    public Optional<SessionBoundary> boundaryAfter(Instant barTime) {
        Objects.requireNonNull(barTime, "barTime");
        if (!availableBarTimes.contains(barTime)) {
            return Optional.empty();
        }
        Map.Entry<Instant, ObservedGap> nextGap = gapsByFinalBar.ceilingEntry(barTime);
        if (nextGap == null || !nextGap.getValue().known()) {
            return Optional.empty();
        }
        return Optional.of(nextGap.getValue().boundary());
    }

    @Override
    public int minutesToClose(Instant barTime) {
        SessionBoundary boundary = boundaryAfter(barTime)
                .orElseThrow(() -> new UnknownSessionBoundaryException(barTime));
        return Math.toIntExact(Duration.between(barTime, boundary.finalExecutableBar()).toMinutes());
    }

    private static List<ObservedGap> gapsIn(List<Instant> times) {
        List<ObservedGap> gaps = new ArrayList<>();
        for (int index = 1; index < times.size(); index++) {
            Instant previous = times.get(index - 1);
            Instant next = times.get(index);
            if (Duration.between(previous, next).compareTo(ONE_MINUTE) > 0) {
                gaps.add(ObservedGap.from(previous, next));
            }
        }
        return gaps;
    }

    private record ObservedGap(SessionBoundary boundary, GapSignature signature, boolean known) {

        private static ObservedGap from(Instant previousBar, Instant nextBar) {
            long roundedGapMinutes = Math.round(Duration.between(previousBar, nextBar).toMillis() / 60_000.0d);
            GapSignature signature = new GapSignature(
                    previousBar.atZone(ZoneOffset.UTC).getDayOfWeek(),
                    previousBar.atZone(ZoneOffset.UTC).getHour() * 60 + previousBar.atZone(ZoneOffset.UTC).getMinute(),
                    nextBar.atZone(ZoneOffset.UTC).getHour() * 60 + nextBar.atZone(ZoneOffset.UTC).getMinute(),
                    roundedGapMinutes);
            return new ObservedGap(new SessionBoundary(previousBar, nextBar), signature, true);
        }

        private ObservedGap asUnknown() {
            return new ObservedGap(boundary, signature, false);
        }
    }

    private record GapSignature(DayOfWeek weekday, int previousMinuteOfDay, int nextMinuteOfDay,
                                long roundedGapMinutes) {
    }
}
