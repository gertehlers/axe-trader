package io.g3tech.axetrader.history;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds where an instrument's minute history really starts, so a seed never records months of
 * "provider empty" intervals before the instrument existed.
 *
 * <p>Probes one 999-minute window starting Wednesday 12:00 UTC per week (every market in scope is open
 * then). The seed starts at the Monday 00:00 UTC of the first week that has data and is followed by a
 * week with data — a single empty week (holiday) never ends the search, and a single stray week never
 * starts it.
 */
public final class HistoryStartProbe {

    static final Duration PROBE_WINDOW = Duration.ofMinutes(999);
    private static final Path UNUSED_STAGING = Path.of("probe-only-no-staging.sqlite");

    private final HistoricalPricePageSource pageSource;

    public HistoryStartProbe(HistoricalPricePageSource pageSource) {
        this.pageSource = Objects.requireNonNull(pageSource, "pageSource");
    }

    public Optional<Instant> firstAvailableFrom(HistoryTarget target, Instant configuredFrom, Instant now) {
        Instant candidate = null;
        for (Instant monday = mondayOf(configuredFrom); !probeStart(monday).plus(PROBE_WINDOW).isAfter(now);
             monday = monday.plus(Duration.ofDays(7))) {
            boolean hasData = hasData(target, probeStart(monday));
            if (hasData && candidate != null) {
                return Optional.of(later(candidate, configuredFrom));
            }
            candidate = hasData ? monday : null;
        }
        return Optional.ofNullable(candidate).map(monday -> later(monday, configuredFrom));
    }

    private boolean hasData(HistoryTarget target, Instant from) {
        Instant to = from.plus(PROBE_WINDOW);
        HistoryImportRequest probe = new HistoryImportRequest(target.epic(), target.resolution(), from, to,
                UNUSED_STAGING, target.source());
        return !pageSource.fetch(probe, from, to, 1_000).prices().isEmpty();
    }

    static Instant mondayOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant probeStart(Instant monday) {
        return monday.plus(Duration.ofDays(2)).plus(Duration.ofHours(12));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
