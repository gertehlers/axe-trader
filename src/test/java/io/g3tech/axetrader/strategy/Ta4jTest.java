package io.g3tech.axetrader.strategy;

import io.g3tech.axetrader.strategy.backtest.repositories.HistoricalPriceRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.aggregator.BaseBarSeriesAggregator;
import org.ta4j.core.aggregator.DurationBarAggregator;
import org.ta4j.core.backtest.BarSeriesManager;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@SpringBootTest
public class Ta4jTest {

    Logger logger = LoggerFactory.getLogger(Ta4jTest.class);

    @Autowired
    private HistoricalPriceRepository historicalPriceRepository;

    @Test
    public void test() {
        logger.info("Testing first strategy");

        var historicalPrices = historicalPriceRepository.findByEpic("US500", Sort.by(Sort.Direction.ASC, "snapshotTimeUtc"), Limit.of(10000));

        var us500_1m = new BaseBarSeriesBuilder().withName("US500_1m").build();

        for (var historicalPrice : historicalPrices) {
            us500_1m.barBuilder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(Instant.parse(historicalPrice.getSnapshotTimeUtc().toString()))
                    .openPrice(mid(historicalPrice.getOpenBid(), historicalPrice.getOpenAsk()))
                    .highPrice(mid(historicalPrice.getHighBid(), historicalPrice.getHighAsk()))
                    .lowPrice(mid(historicalPrice.getLowBid(), historicalPrice.getLowAsk()))
                    .closePrice(mid(historicalPrice.getCloseBid(), historicalPrice.getCloseAsk()))
                    .volume(historicalPrice.getLastTradedVolume())
                    .add();
        }

        var aggregator = new BaseBarSeriesAggregator(new DurationBarAggregator(Duration.ofMinutes(5), true));
        var us500_5m = aggregator.aggregate(us500_1m, "US500_5m");

        ClosePriceIndicator closePrice = new ClosePriceIndicator(us500_5m);

        // indicators
        RSIIndicator rsi7 = new RSIIndicator(closePrice, 7);
        SMAIndicator smoothRsi7 = new SMAIndicator(rsi7, 7);


        // strategies
//        Strategy strategy = new BaseStrategy("SMA crossover", entryRule, exitRule);
//        strategy.setUnstableBars(100);

        BarSeriesManager manager = new BarSeriesManager(us500_1m);
//        TradingRecord record = manager.run(strategy);

//        System.out.printf("Closed positions: %d%n", record.getPositionCount());
//        System.out.printf("Current position open? %s%n", record.getCurrentPosition().isOpened());

//        AnalysisCriterion netReturn = new NetReturnCriterion();
//        AnalysisCriterion romad = new ReturnOverMaxDrawdownCriterion();
//        AnalysisCriterion openCostBasis = new OpenPositionCostBasisCriterion();
//
//        System.out.println("Net return: " + netReturn.calculate(us500BarSeries, record));
//        System.out.println("Return over max drawdown: " + romad.calculate(us500BarSeries, record));
//        System.out.println("Open position cost basis: " + openCostBasis.calculate(us500BarSeries, record));

//        ChartWorkflow chartWorkflow = new ChartWorkflow();
//        JFreeChart chart = chartWorkflow.builder()
//                .withTitle("RSI Indicator with SMA smoothing")
//                .withTimeAxisMode(TimeAxisMode.BAR_INDEX) // Optional: compress non-trading gaps (weekends/holidays)
//                .withSeries(us500_5m) // Price bars (candlesticks)
////                .withIndicatorOverlay(rsi7) // Overlay indicators on price chart
////                .withIndicatorOverlay(smoothRsi7)
////                .withTradingRecordOverlay(record) // Mark entry/exit points with arrows
//                .withSubChart(rsi7)
//                .withSubChart(smoothRsi7)
//                .toChart();
//        chartWorkflow.saveChartImage(chart, us500_5m, "rsi-indicator", "output/charts"); // Save as image

        saveInteractiveChart(us500_5m, rsi7, smoothRsi7, "output/charts");
    }

    // After calculating your indicators, export to HTML
    public void saveInteractiveChart(BarSeries series, RSIIndicator rsi, SMAIndicator smoothRsi, String outputPath) {
        int unstable = 14; // skip first N bars where RSI isn't meaningful yet

        StringBuilder candleData    = new StringBuilder("[");
        StringBuilder rsiData       = new StringBuilder("[");
        StringBuilder smoothRsiData = new StringBuilder("[");

        for (int i = unstable; i < series.getBarCount(); i++) {
            var bar  = series.getBar(i);
            long time = bar.getEndTime().getEpochSecond();

            candleData.append(String.format(Locale.US,
                    "{time:%d,open:%.5f,high:%.5f,low:%.5f,close:%.5f},",
                    time,
                    bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(),
                    bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue()
            ));
            rsiData.append(String.format(Locale.US,
                    "{time:%d,value:%.4f},", time, rsi.getValue(i).doubleValue()));
            smoothRsiData.append(String.format(Locale.US,
                    "{time:%d,value:%.4f},", time, smoothRsi.getValue(i).doubleValue()));
        }

        // strip trailing commas
        candleData.deleteCharAt(candleData.length() - 1);    candleData.append("]");
        rsiData.deleteCharAt(rsiData.length() - 1);          rsiData.append("]");
        smoothRsiData.deleteCharAt(smoothRsiData.length() - 1); smoothRsiData.append("]");

        String html = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="UTF-8"/>
          <style>
            body { background: #131722; margin: 0; padding: 0; }
            #price { height: 60vh; }
            #rsi   { height: 35vh; margin-top: 4px; }
          </style>
        </head>
        <body>
          <div id="price"></div>
          <div id="rsi"></div>
          <script src="https://unpkg.com/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
          <script>
            const candleData    = %s;
            const rsiData       = %s;
            const smoothRsiData = %s;

            const priceChart = LightweightCharts.createChart(document.getElementById('price'), {
              layout: { background: { color: '#131722' }, textColor: '#d1d4dc' },
              width:  window.innerWidth,
              height: window.innerHeight * 0.60,
              grid:   { vertLines: { color: '#2B2B43' }, horzLines: { color: '#2B2B43' } }
            });
            priceChart.addCandlestickSeries().setData(candleData);
            priceChart.timeScale().fitContent();

            const rsiChart = LightweightCharts.createChart(document.getElementById('rsi'), {
              layout: { background: { color: '#131722' }, textColor: '#d1d4dc' },
              width:  window.innerWidth,
              height: window.innerHeight * 0.35,
              grid:   { vertLines: { color: '#2B2B43' }, horzLines: { color: '#2B2B43' } }
            });
            const rsiSeries       = rsiChart.addLineSeries({ color: '#2962ff', lineWidth: 1 });
            const smoothRsiSeries = rsiChart.addLineSeries({ color: '#ff6d00', lineWidth: 2 });
            rsiSeries.setData(rsiData);
            smoothRsiSeries.setData(smoothRsiData);

            // overbought / oversold bands
            rsiSeries.createPriceLine({ price: 70, color: 'rgba(239,83,80,0.8)',  lineWidth: 1, lineStyle: 1, axisLabelVisible: true, title: 'OB 70' });
            rsiSeries.createPriceLine({ price: 30, color: 'rgba(38,166,154,0.8)', lineWidth: 1, lineStyle: 1, axisLabelVisible: true, title: 'OS 30' });

            // shaded fill above 70 (red) and below 30 (green)
            const times = rsiData.map(d => d.time);
            const ob70  = times.map(t => ({ time: t, value: 70 }));
            const os30  = times.map(t => ({ time: t, value: 30 }));
            rsiChart.addLineSeries({ color: 'rgba(239,83,80,0.15)',  lineWidth: 0, lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false }).setData(ob70);
            rsiChart.addLineSeries({ color: 'rgba(38,166,154,0.15)', lineWidth: 0, lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false }).setData(os30);

            rsiChart.timeScale().fitContent();

            // sync zoom/scroll between charts
            function syncCharts(source, targets) {
              source.timeScale().subscribeVisibleLogicalRangeChange(range => {
                if (range) targets.forEach(t => t.timeScale().setVisibleLogicalRange(range));
              });
            }
            syncCharts(priceChart, [rsiChart]);
            syncCharts(rsiChart,   [priceChart]);
          </script>
        </body>
        </html>
        """.formatted(candleData, rsiData, smoothRsiData);

        try {
            var dir = Path.of(outputPath);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("chart.html"), html);
            logger.info("Interactive chart saved to {}/chart.html", outputPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void test2() {
//        logger.info("Testing wseqw21first strategy");
//
//        var historicalPrices = historicalPriceRepository.findByEpic("US500", Sort.by(Sort.Direction.ASC, "snapshotTimeUtc"), Limit.of(10000));
//
//        var us500BarSeries = new BaseBarSeriesBuilder().withName("US500").build();
//
//        for (var historicalPrice : historicalPrices) {
//            us500BarSeries.barBuilder()
//                    .timePeriod(Duration.ofMinutes(1))
//                    .endTime(Instant.parse(historicalPrice.getSnapshotTimeUtc().toString()))
//                    .openPrice(mid(historicalPrice.getOpenBid(), historicalPrice.getOpenAsk()))
//                    .highPrice(mid(historicalPrice.getHighBid(), historicalPrice.getHighAsk()))
//                    .lowPrice(mid(historicalPrice.getLowBid(), historicalPrice.getLowAsk()))
//                    .closePrice(mid(historicalPrice.getCloseBid(), historicalPrice.getCloseAsk()))
//                    .volume(historicalPrice.getLastTradedVolume())
//                    .add();
//        }


//        ClosePriceIndicator closePrice = new ClosePriceIndicator(us500BarSeries);
//        SMAIndicator sma200 = new SMAIndicator(closePrice, 200);
//        SMAIndicator sma400 = new SMAIndicator(closePrice, 400);
//        Rule trendFilter = new OverIndicatorRule(sma200, sma400);
//        Rule momentumKick = new CrossedUpIndicatorRule(macd, signalLine);
//        Rule renkoBreak = new BooleanIndicatorRule(new RenkoUpIndicator(closePrice, brickSize, 2));
//
//        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
//        Indicator<Num> netMomentum = NetMomentumIndicator.forRsi(rsi, 20);
//        Rule entryRule = new VoteRule(2, trendFilter, momentumKick, renkoBreak)
//                .and(new OverIndicatorRule(netMomentum, series.numFactory().zero()));
//
//        Rule timedMomentumExit = new OrWithThresholdRule(
//                new CrossedDownIndicatorRule(macd, signalLine),
//                new InSlopeRule(netMomentum, 3, us500BarSeries.numFactory().numOf("-10")),
//                3);
//        Rule exitRule = timedMomentumExit
//                .or(new StopLossRule(closePrice, us500BarSeries.numFactory().numOf(3)))
//                .or(new StopGainRule(closePrice, us500BarSeries.numFactory().numOf(5)));;
//
//        Strategy strategy = new BaseStrategy("SMA crossover", entryRule, exitRule);
//        strategy.setUnstableBars(100);
//
//        BarSeriesManager manager = new BarSeriesManager(us500BarSeries);
//        TradingRecord record = manager.run(strategy);
//
//        System.out.printf("Closed positions: %d%n", record.getPositionCount());
//        System.out.printf("Current position open? %s%n", record.getCurrentPosition().isOpened());
//
//        AnalysisCriterion netReturn = new NetReturnCriterion();
//        AnalysisCriterion romad = new ReturnOverMaxDrawdownCriterion();
//        AnalysisCriterion openCostBasis = new OpenPositionCostBasisCriterion();
//
//        System.out.println("Net return: " + netReturn.calculate(us500BarSeries, record));
//        System.out.println("Return over max drawdown: " + romad.calculate(us500BarSeries, record));
//        System.out.println("Open position cost basis: " + openCostBasis.calculate(us500BarSeries, record));
//
//        ChartWorkflow chartWorkflow = new ChartWorkflow();
//        JFreeChart chart = chartWorkflow.builder()
//                .withTitle("EMA Crossover Strategy")
//                .withTimeAxisMode(TimeAxisMode.BAR_INDEX) // Optional: compress non-trading gaps (weekends/holidays)
//                .withSeries(us500BarSeries) // Price bars (candlesticks)
//                .withIndicatorOverlay(fastSma) // Overlay indicators on price chart
//                .withIndicatorOverlay(slowSma)
//                .withTradingRecordOverlay(record) // Mark entry/exit points with arrows
//                .toChart();
//        chartWorkflow.saveChartImage(chart, us500BarSeries, "ema-crossover-strategy", "output/charts"); // Save as image

    }

    private double mid(double openBid, double openAsk) {
        return (openBid + openAsk) / 2;
    }
}
