package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryDeltaMergerRevisionTest {

    @TempDir
    Path directory;

    @Test
    void replacesARevisedBarAndRecordsTheRevision() throws Exception {
        Path active = database("active.sqlite", "run-a", "2026-08-06T09:00:00Z", 100.0);
        Path delta = database("delta.sqlite", "run-b", "2026-08-06T09:00:00Z", 101.5);

        HistoryDeltaMerger.MergeResult result = new HistoryDeltaMerger().merge(delta, active);

        assertThat(result.pricesRevised()).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + active)) {
            var price = connection.createStatement().executeQuery(
                    "SELECT close_bid, COUNT(*) FROM historical_price");
            assertThat(price.getDouble(1)).isEqualTo(101.5);
            assertThat(price.getLong(2)).isEqualTo(1);
            var revision = connection.createStatement().executeQuery(
                    "SELECT snapshot_time_utc, old_values, new_values FROM price_revision");
            assertThat(revision.next()).isTrue();
            assertThat(revision.getString(1)).isEqualTo("2026-08-06T09:00:00Z");
            assertThat(revision.getString(2)).contains("100");
            assertThat(revision.getString(3)).contains("101.5");
        }
    }

    @Test
    void anIdenticalOverlapRecordsNoRevision() throws Exception {
        Path active = database("active.sqlite", "run-a", "2026-08-06T09:00:00Z", 100.0);
        Path delta = database("delta.sqlite", "run-b", "2026-08-06T09:00:00Z", 100.0);

        assertThat(new HistoryDeltaMerger().merge(delta, active).pricesRevised()).isZero();
    }

    private Path database(String name, String runId, String minute, double closeBid) throws Exception {
        Path path = directory.resolve(name);
        HistoryStagingStore.open(path).close();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            connection.createStatement().execute("INSERT INTO history_import_run VALUES ('" + runId
                    + "', 4, 'capital', 'OIL_BRENT', 'MINUTE', '2026-08-06T08:00:00Z', '2026-08-06T10:00:00Z', "
                    + "'2026-08-06T10:00:00Z')");
            connection.createStatement().execute("INSERT INTO historical_price VALUES ('" + runId + "-p', "
                    + "'OIL_BRENT', 'MINUTE', '" + minute + "', 100, 100.04, 100.2, 100.24, 99.9, 99.94, "
                    + closeBid + ", " + (closeBid + 0.04) + ", 10, 'capital', '2026-08-06T10:00:00Z')");
        }
        return path;
    }
}
