package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryStartProbeTest {

    private static final HistoryTarget TARGET = new HistoryTarget("capital", "GOLD", "MINUTE");
    private static final Instant NOW = Instant.parse("2024-03-01T00:00:00Z");

    @Test
    void startsAtTheMondayOfTheFirstWeekWithDataFollowedByAnotherWeekWithData() {
        // Wednesdays 2024-01-03 (no data), 01-10 (data), 01-17 (no data: holiday-like), 01-24 (data), 01-31 (data)
        FakeSource source = new FakeSource(Set.of(
                Instant.parse("2024-01-10T12:00:00Z"),
                Instant.parse("2024-01-24T12:00:00Z"),
                Instant.parse("2024-01-31T12:00:00Z")));

        assertThat(new HistoryStartProbe(source).firstAvailableFrom(TARGET, Instant.parse("2024-01-01T00:00:00Z"), NOW))
                .contains(Instant.parse("2024-01-22T00:00:00Z"));
    }

    @Test
    void neverStartsBeforeTheConfiguredStart() {
        FakeSource source = new FakeSource(Set.of(
                Instant.parse("2024-01-03T12:00:00Z"), Instant.parse("2024-01-10T12:00:00Z")));

        assertThat(new HistoryStartProbe(source).firstAvailableFrom(TARGET, Instant.parse("2024-01-02T05:00:00Z"), NOW))
                .contains(Instant.parse("2024-01-02T05:00:00Z"));
    }

    @Test
    void reportsNoHistoryWhenNoWeekHasData() {
        FakeSource source = new FakeSource(Set.of());

        assertThat(new HistoryStartProbe(source).firstAvailableFrom(TARGET, Instant.parse("2024-01-01T00:00:00Z"), NOW))
                .isEmpty();
        assertThat(source.probedFrom).first().isEqualTo(Instant.parse("2024-01-03T12:00:00Z"));
    }

    private static final class FakeSource implements HistoricalPricePageSource {
        private final Set<Instant> withData;
        private final List<Instant> probedFrom = new ArrayList<>();

        FakeSource(Set<Instant> withData) {
            this.withData = withData;
        }

        @Override
        public ImportedPage fetch(HistoryImportRequest request, Instant from, Instant to, int maxBars) {
            probedFrom.add(from);
            List<ImportedPrice> prices = withData.contains(from)
                    ? List.of(new ImportedPrice(from, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L))
                    : List.of();
            return new ImportedPage(from, to, prices, "probe-" + from);
        }
    }
}
