package io.g3tech.axetrader.history;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

final class HistoryCoverage {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private HistoryCoverage() {
    }

    static Assessment assess(Instant requestedFrom, Instant requestedTo, List<Page> pages) {
        List<HistoryCoverageGap> closures = new ArrayList<>();
        List<HistoryCoverageGap> gaps = new ArrayList<>();
        Instant cursor = requestedFrom;
        for (Page page : pages.stream().sorted(Comparator.comparing(Page::fromInclusive)).toList()) {
            if (page.fromInclusive().isAfter(cursor)) {
                gaps.add(new HistoryCoverageGap(cursor, page.fromInclusive()));
            }
            assessPage(page, closures, gaps);
            if (page.toExclusive().isAfter(cursor)) {
                cursor = page.toExclusive();
            }
        }
        if (cursor.isBefore(requestedTo)) {
            gaps.add(new HistoryCoverageGap(cursor, requestedTo));
        }
        return new Assessment(List.copyOf(closures), List.copyOf(gaps));
    }

    private static void assessPage(Page page, List<HistoryCoverageGap> closures, List<HistoryCoverageGap> gaps) {
        TreeSet<Instant> observed = new TreeSet<>();
        for (Instant timestamp : page.observedTimestamps()) {
            if (timestamp != null && !timestamp.isBefore(page.fromInclusive()) && timestamp.isBefore(page.toExclusive())) {
                observed.add(timestamp);
            }
        }
        Instant cursor = page.fromInclusive();
        boolean first = true;
        for (Instant timestamp : observed) {
            if (cursor.isBefore(timestamp)) {
                (first ? closures : gaps).add(new HistoryCoverageGap(cursor, timestamp));
            }
            cursor = timestamp.plus(MINUTE);
            first = false;
        }
        if (cursor.isBefore(page.toExclusive())) {
            closures.add(new HistoryCoverageGap(cursor, page.toExclusive()));
        }
    }

    record Page(Instant fromInclusive, Instant toExclusive, List<Instant> observedTimestamps) {
        Page {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            observedTimestamps = List.copyOf(observedTimestamps);
        }
    }

    record Assessment(List<HistoryCoverageGap> recognizedSessionClosures, List<HistoryCoverageGap> continuityGaps) {
    }
}
