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
    }
}
