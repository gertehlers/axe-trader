package io.g3tech.axetrader.backtest.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.g3tech.axetrader.backtest.config.BacktestProperties;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.runner.TradeFeatures;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import io.g3tech.axetrader.backtest.runner.VolatilityRegime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

class DashboardExporterTest {

    private BarSeries seriesOf(Instant start, int count, double price) {
        BarSeries s = new BaseBarSeriesBuilder().withName("t").build();
        for (int i = 0; i < count; i++) {
            s.barBuilder()
                    .timePeriod(Duration.ofMinutes(5))
                    .endTime(start.plus(Duration.ofMinutes(5L * (i + 1))))
                    .openPrice(price)
                    .highPrice(price + 1)
                    .lowPrice(price - 1)
                    .closePrice(price)
                    .volume(100)
                    .add();
        }
        return s;
    }

    @Test
    void writesRunJsonWithDerivedStopTargetAndBarWindow(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2025-03-04T14:00:00Z");
        BarSeries series = seriesOf(t0, 200, 5000);
        ZonedDateTime entry = t0.plus(Duration.ofMinutes(5L * 100)).atZone(ZoneOffset.UTC);
        ZonedDateTime exit = t0.plus(Duration.ofMinutes(5L * 110)).atZone(ZoneOffset.UTC);

        TradeFeatures f = new TradeFeatures(28, 0, 0, 0, 0, 1.2, 0, 4.0, 0.6, 1.0, 14, 2, 3);
        TradeResult tr = new TradeResult(entry, exit, Direction.LONG, 5000, 5003, 3.0, 0.75,
                VolatilityRegime.NORMAL, true, ExitReason.TARGET, f, List.of("RSI", "BB", "S/R"));

        BacktestProperties.Strategy cfg = new BacktestProperties.Strategy();
        cfg.setStopAtrMultiple(3.0);
        cfg.setTargetAtrMultiple(0.75);

        Path out = dir.resolve("run.json");
        DashboardExporter.export(out, "test", "US500-1", cfg, "{}", "US500", 5,
                t0, t0.plus(Duration.ofDays(1)), 1.0, 0.5, 1, series, List.of(tr));

        JsonNode root = new ObjectMapper().readTree(Files.readString(out));
        JsonNode t = root.get("trades").get(0);
        assertThat(t.get("signal_key").asText())
                .isEqualTo("US500|" + entry.toInstant() + "|LONG");
        // LONG stop = entry - 3.0*atr(4) = 4988 ; target = entry + 0.75*4 = 5003
        assertThat(t.get("stop_price").asDouble()).isEqualTo(4988.0);
        assertThat(t.get("target_price").asDouble()).isEqualTo(5003.0);
        // window: 50 bars before entry-index .. 50 after exit-index, all present
        assertThat(t.get("bars").isArray()).isTrue();
        assertThat(t.get("bars").size()).isGreaterThan(50);
        assertThat(root.get("run").get("net_avg_pnl_usd").asDouble())
                .isEqualTo(root.get("run").get("net_avg_pnl").asDouble()); // valuePerPoint=1.0

        // net_pnl must be genuinely net: raw pnl (3.0) less avgSpread (0.5). This column shipped
        // holding raw pnl for a while -- nothing asserted on it -- and the dashboard's equity curve
        // cumulates it under a "net" heading, so the chart sloped upward while the net-expectancy
        // KPI beside it read negative. Keep pnl and net_pnl asserted as DIFFERENT values so the
        // two can never silently collapse back together.
        assertThat(t.get("pnl").asDouble()).isEqualTo(3.0);
        assertThat(t.get("net_pnl").asDouble()).isEqualTo(2.5);
        assertThat(root.get("run").get("net_avg_pnl").asDouble()).isEqualTo(2.5);
        // is_win stays GROSS by design (it matches ConfluenceSweepTest's win rate), so a trade can
        // be a win with a smaller net figure -- that gap is the spread.
        assertThat(t.get("is_win").asInt()).isEqualTo(1);
    }

    @Test
    void computesMaxDrawdownAndWorstQuarterNetFromNetPnlOrderedByEntryTime(@TempDir Path dir)
            throws Exception {
        // Six trades, avgSpread=0.5, entry-ordered net pnl (pnl - avgSpread): +2, +3, -1, -3, +6, -2
        // spanning three calendar quarters of 2025 (Q1, Q2, Q3).
        //
        // Cumulative NET equity curve (peak anchored at 0):
        //   after t1: cum=2   peak=2   drawdown=0
        //   after t2: cum=5   peak=5   drawdown=0
        //   after t3: cum=4   peak=5   drawdown=1
        //   after t4: cum=1   peak=5   drawdown=4   <- deepest peak-to-trough decline
        //   after t5: cum=7   peak=7   drawdown=0
        //   after t6: cum=5   peak=7   drawdown=2
        // max_drawdown = 4.0
        //
        // This is deliberately NOT equal to:
        //   - the single largest losing trade's net pnl (-3, at t4) -> a "worst single trade" bug
        //     would report 3.0, not 4.0.
        //   - final cumulative (5) minus overall peak (7) = 2.0 -> a "final minus peak" bug (instead
        //     of tracking the running peak throughout) would report 2.0, not 4.0.
        //
        // Quarter sums (net pnl):
        //   Q1 2025 (t1 + t2): 2 + 3 = 5
        //   Q2 2025 (t3 + t4): -1 + -3 = -4   <- worst quarter
        //   Q3 2025 (t5 + t6): 6 + -2 = 4
        // worst_quarter_net = -4.0
        //
        // This is deliberately NOT equal to:
        //   - the total net pnl across all trades (5+(-4)+4 = 5) -> a bug that ignores quarter
        //     grouping entirely would report 5.0, not -4.0.
        //   - the single worst trade's net pnl (-3, at t4) -> a bug that reports "worst trade" instead
        //     of "worst quarter total" would report -3.0, not -4.0.
        double avgSpread = 0.5;
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        BarSeries series = seriesOf(t0, 200, 5000);

        double[] rawPnl = {2.5, 3.5, -0.5, -2.5, 6.5, -1.5};
        String[] entryIso = {
                "2025-01-10T10:00:00Z", "2025-02-10T10:00:00Z",
                "2025-04-10T10:00:00Z", "2025-05-10T10:00:00Z",
                "2025-07-10T10:00:00Z", "2025-08-10T10:00:00Z"
        };
        List<TradeResult> trades = new java.util.ArrayList<>();
        for (int i = 0; i < rawPnl.length; i++) {
            ZonedDateTime entry = Instant.parse(entryIso[i]).atZone(ZoneOffset.UTC);
            ZonedDateTime exit = entry.plus(Duration.ofMinutes(10));
            trades.add(new TradeResult(entry, exit, Direction.LONG, 5000, 5000 + rawPnl[i],
                    rawPnl[i], 0, VolatilityRegime.NORMAL, rawPnl[i] > 0, ExitReason.TARGET, null,
                    List.of()));
        }

        BacktestProperties.Strategy cfg = new BacktestProperties.Strategy();
        cfg.setStopAtrMultiple(3.0);
        cfg.setTargetAtrMultiple(0.75);

        Path out = dir.resolve("run.json");
        DashboardExporter.export(out, "test", "US500-2", cfg, "{}", "US500", 5,
                t0, t0.plus(Duration.ofDays(240)), 1.0, avgSpread, 1, series, trades);

        JsonNode root = new ObjectMapper().readTree(Files.readString(out));
        assertThat(root.get("run").get("max_drawdown").asDouble()).isEqualTo(4.0);
        assertThat(root.get("run").get("worst_quarter_net").asDouble()).isEqualTo(-4.0);
    }

    @Test
    void reportsZeroMaxDrawdownAndZeroWorstQuarterWithNoTrades(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        BarSeries series = seriesOf(t0, 10, 5000);
        BacktestProperties.Strategy cfg = new BacktestProperties.Strategy();

        Path out = dir.resolve("run.json");
        DashboardExporter.export(out, "test", "US500-3", cfg, "{}", "US500", 5,
                t0, t0.plus(Duration.ofDays(1)), 1.0, 0.0, 1, series, List.of());

        JsonNode root = new ObjectMapper().readTree(Files.readString(out));
        assertThat(root.get("run").get("max_drawdown").asDouble()).isEqualTo(0.0);
        assertThat(root.get("run").get("worst_quarter_net").asDouble()).isEqualTo(0.0);
    }
}
