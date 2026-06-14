package io.g3tech.axetrader.backtest.chart;

import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.TradeResult;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class BacktestChartExporter {

    public void export(
            BarSeries series,
            IndicatorBundle indicators,
            List<TradeResult> trades,
            String outputPath,
            String filename) {
        try {
            Path directory = Path.of(outputPath);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(filename + ".html"), html(series, indicators, trades));
        } catch (IOException e) {
            throw new RuntimeException("Failed to export backtest chart", e);
        }
    }

    private String html(BarSeries series, IndicatorBundle indicators, List<TradeResult> trades) {
        String candleData = candleData(series);
        String emaData = lineData(series, indicators.ema::getValue);
        String bbMiddleData = lineData(series, indicators.bbMiddle::getValue);
        String bbUpperData = lineData(series, indicators.bbUpper::getValue);
        String bbLowerData = lineData(series, indicators.bbLower::getValue);
        String rsiData = lineData(series, indicators.rsi::getValue);
        String smoothRsiData = lineData(series, indicators.smoothRsi::getValue);
        String tradeMarkers = tradeMarkers(trades);
        String longEntryData = tradePointData(trades, TradePoint.LONG_ENTRY);
        String shortEntryData = tradePointData(trades, TradePoint.SHORT_ENTRY);
        String stopGainData = tradePointData(trades, TradePoint.STOP_GAIN);
        String stopLossData = tradePointData(trades, TradePoint.STOP_LOSS);
        String operationLabels = operationLabels(trades);
        String tradeVisibleRange = tradeVisibleRange(trades);
        String title = title(series, trades);

        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8"/>
                  <title>${title}</title>
                  <style>
                    html, body {
                      height: 100%;
                      margin: 0;
                      background: #131722;
                      color: #d1d4dc;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                    }
                    #header {
                      height: 40px;
                      display: flex;
                      align-items: center;
                      gap: 18px;
                      padding: 0 14px;
                      border-bottom: 1px solid #2b2b43;
                      box-sizing: border-box;
                      font-size: 13px;
                      white-space: nowrap;
                    }
                    #price {
                      height: calc(62vh - 40px);
                      position: relative;
                    }
                    #rsi { height: 36vh; border-top: 1px solid #2b2b43; }
                    .trade-label {
                      position: absolute;
                      transform: translate(-50%, calc(-100% - 12px));
                      z-index: 10;
                      min-width: 92px;
                      padding: 5px 7px;
                      border: 1px solid;
                      border-radius: 4px;
                      background: rgba(19, 23, 34, 0.94);
                      color: #fff;
                      font-size: 11px;
                      line-height: 1.2;
                      font-weight: 650;
                      text-align: center;
                      white-space: nowrap;
                      pointer-events: none;
                      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35);
                    }
                    .trade-label::after {
                      content: "";
                      position: absolute;
                      left: 50%;
                      bottom: -8px;
                      transform: translateX(-50%);
                      width: 0;
                      height: 0;
                      border-left: 6px solid transparent;
                      border-right: 6px solid transparent;
                      border-top: 8px solid var(--pointer-color);
                    }
                  </style>
                </head>
                <body>
                  <div id="header">${title}</div>
                  <div id="price"></div>
                  <div id="rsi"></div>
                  <script src="https://unpkg.com/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
                  <script>
                    const candleData = ${candleData};
                    const emaData = ${emaData};
                    const bbMiddleData = ${bbMiddleData};
                    const bbUpperData = ${bbUpperData};
                    const bbLowerData = ${bbLowerData};
                    const rsiData = ${rsiData};
                    const smoothRsiData = ${smoothRsiData};
                    const tradeMarkers = ${tradeMarkers};
                    const longEntryData = ${longEntryData};
                    const shortEntryData = ${shortEntryData};
                    const stopGainData = ${stopGainData};
                    const stopLossData = ${stopLossData};
                    const operationLabels = ${operationLabels};
                    const tradeVisibleRange = ${tradeVisibleRange};

                    const chartOptions = {
                      layout: { background: { color: '#131722' }, textColor: '#d1d4dc' },
                      grid: { vertLines: { color: '#2b2b43' }, horzLines: { color: '#2b2b43' } },
                      crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                      rightPriceScale: { borderColor: '#363c4e' },
                      timeScale: { borderColor: '#363c4e', timeVisible: true, secondsVisible: false }
                    };

                    const priceElement = document.getElementById('price');
                    const rsiElement = document.getElementById('rsi');
                    const priceChart = LightweightCharts.createChart(priceElement, {
                      ...chartOptions,
                      width: priceElement.clientWidth,
                      height: priceElement.clientHeight
                    });
                    const candleSeries = priceChart.addCandlestickSeries({
                      upColor: '#26a69a',
                      downColor: '#ef5350',
                      borderUpColor: '#26a69a',
                      borderDownColor: '#ef5350',
                      wickUpColor: '#26a69a',
                      wickDownColor: '#ef5350'
                    });
                    candleSeries.setData(candleData);
                    candleSeries.setMarkers(tradeMarkers);
                    priceChart.addLineSeries({ color: '#f5c542', lineWidth: 1, priceLineVisible: false }).setData(emaData);
                    priceChart.addLineSeries({ color: 'rgba(41, 98, 255, 0.85)', lineWidth: 1, priceLineVisible: false }).setData(bbMiddleData);
                    priceChart.addLineSeries({ color: 'rgba(156, 204, 101, 0.8)', lineWidth: 1, priceLineVisible: false }).setData(bbUpperData);
                    priceChart.addLineSeries({ color: 'rgba(156, 204, 101, 0.8)', lineWidth: 1, priceLineVisible: false }).setData(bbLowerData);
                    const rsiChart = LightweightCharts.createChart(rsiElement, {
                      ...chartOptions,
                      width: rsiElement.clientWidth,
                      height: rsiElement.clientHeight
                    });
                    const rsiSeries = rsiChart.addLineSeries({ color: '#2962ff', lineWidth: 1 });
                    const smoothRsiSeries = rsiChart.addLineSeries({ color: '#ff9800', lineWidth: 2 });
                    rsiSeries.setData(rsiData);
                    smoothRsiSeries.setData(smoothRsiData);
                    rsiSeries.createPriceLine({ price: 70, color: 'rgba(239,83,80,0.85)', lineWidth: 1, lineStyle: 1, title: '70' });
                    rsiSeries.createPriceLine({ price: 30, color: 'rgba(38,166,154,0.85)', lineWidth: 1, lineStyle: 1, title: '30' });

                    function syncCharts(source, targets) {
                      source.timeScale().subscribeVisibleLogicalRangeChange(range => {
                        if (range) {
                          targets.forEach(target => target.timeScale().setVisibleLogicalRange(range));
                        }
                      });
                    }
                    syncCharts(priceChart, [rsiChart]);
                    syncCharts(rsiChart, [priceChart]);

                    function resizeCharts() {
                      priceChart.applyOptions({ width: priceElement.clientWidth, height: priceElement.clientHeight });
                      rsiChart.applyOptions({ width: rsiElement.clientWidth, height: rsiElement.clientHeight });
                      renderOperationLabels();
                    }
                    window.addEventListener('resize', resizeCharts);
                    if (tradeVisibleRange) {
                      priceChart.timeScale().setVisibleRange(tradeVisibleRange);
                      rsiChart.timeScale().setVisibleRange(tradeVisibleRange);
                    } else {
                      priceChart.timeScale().fitContent();
                      rsiChart.timeScale().fitContent();
                    }
                    priceChart.timeScale().subscribeVisibleLogicalRangeChange(renderOperationLabels);
                    setTimeout(renderOperationLabels, 0);

                    function renderOperationLabels() {
                      priceElement.querySelectorAll('.trade-label').forEach(label => label.remove());
                      operationLabels.forEach(operation => {
                        const x = priceChart.timeScale().timeToCoordinate(operation.time);
                        const y = candleSeries.priceToCoordinate(operation.price);
                        if (x === null || y === null) return;

                        const label = document.createElement('div');
                        label.className = 'trade-label';
                        label.style.left = `${x}px`;
                        label.style.top = `${y}px`;
                        label.style.borderColor = operation.color;
                        label.style.setProperty('--pointer-color', operation.color);
                        label.textContent = operation.label;
                        priceElement.appendChild(label);
                      });
                    }
                  </script>
                </body>
                </html>
                """
                .replace("${title}", escapeHtml(title))
                .replace("${candleData}", candleData)
                .replace("${emaData}", emaData)
                .replace("${bbMiddleData}", bbMiddleData)
                .replace("${bbUpperData}", bbUpperData)
                .replace("${bbLowerData}", bbLowerData)
                .replace("${rsiData}", rsiData)
                .replace("${smoothRsiData}", smoothRsiData)
                .replace("${tradeMarkers}", tradeMarkers)
                .replace("${longEntryData}", longEntryData)
                .replace("${shortEntryData}", shortEntryData)
                .replace("${stopGainData}", stopGainData)
                .replace("${stopLossData}", stopLossData)
                .replace("${operationLabels}", operationLabels)
                .replace("${tradeVisibleRange}", tradeVisibleRange);
    }

    private static String candleData(BarSeries series) {
        StringBuilder data = new StringBuilder("[");
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            var bar = series.getBar(i);
            appendComma(data);
            data.append(String.format(Locale.US,
                    "{time:%d,open:%.5f,high:%.5f,low:%.5f,close:%.5f}",
                    bar.getEndTime().getEpochSecond(),
                    bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(),
                    bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue()));
        }
        return data.append("]").toString();
    }

    private static String lineData(BarSeries series, IndicatorValue value) {
        StringBuilder data = new StringBuilder("[");
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            appendComma(data);
            data.append(String.format(Locale.US,
                    "{time:%d,value:%.5f}",
                    series.getBar(i).getEndTime().getEpochSecond(),
                    value.get(i).doubleValue()));
        }
        return data.append("]").toString();
    }

    private static String tradeMarkers(List<TradeResult> trades) {
        StringBuilder markers = new StringBuilder("[");
        trades.stream()
                .flatMap(trade -> List.of(entryMarker(trade), exitMarker(trade)).stream())
                .sorted(Comparator.comparingLong(BacktestChartExporter::markerTime))
                .forEach(marker -> {
                    appendComma(markers);
                    markers.append(marker);
                });
        return markers.append("]").toString();
    }

    private static String entryMarker(TradeResult trade) {
        boolean isLong = trade.direction() == Direction.LONG;
        return String.format(Locale.US,
                "{time:%d,position:'%s',color:'%s',shape:'%s',text:'%s'}",
                epochSecond(trade.entryTime()),
                isLong ? "belowBar" : "aboveBar",
                isLong ? "#2962ff" : "#ff8a80",
                isLong ? "arrowUp" : "arrowDown",
                isLong ? "LONG" : "SHORT");
    }

    private static String exitMarker(TradeResult trade) {
        return String.format(Locale.US,
                "{time:%d,position:'aboveBar',color:'%s',shape:'arrowDown',text:'%s %.2fR'}",
                epochSecond(trade.exitTime()),
                exitColor(trade),
                trade.isWin() ? "Gain" : "Stop",
                trade.rMultiple());
    }

    private static String tradePointData(List<TradeResult> trades, TradePoint point) {
        StringBuilder data = new StringBuilder("[");
        trades.stream()
                .filter(trade -> point.includes(trade))
                .sorted(Comparator.comparingLong(trade -> point.time(trade)))
                .forEach(trade -> {
                    appendComma(data);
                    data.append(String.format(Locale.US,
                            "{time:%d,value:%.5f}",
                            point.time(trade),
                            point.price(trade)));
                });
        return data.append("]").toString();
    }

    private static String operationLabels(List<TradeResult> trades) {
        StringBuilder labels = new StringBuilder("[");
        trades.stream()
                .flatMap(trade -> List.of(entryLabel(trade), exitLabel(trade)).stream())
                .sorted(Comparator.comparingLong(BacktestChartExporter::labelTime))
                .forEach(label -> {
                    appendComma(labels);
                    labels.append(label);
                });
        return labels.append("]").toString();
    }

    private static String entryLabel(TradeResult trade) {
        boolean isLong = trade.direction() == Direction.LONG;
        return operationLabel(
                epochSecond(trade.entryTime()),
                trade.entryPrice(),
                isLong ? "#2962ff" : "#ff8a80",
                String.format(Locale.US, "%s entry %.2f", isLong ? "Long" : "Short", trade.entryPrice()));
    }

    private static String exitLabel(TradeResult trade) {
        return operationLabel(
                epochSecond(trade.exitTime()),
                trade.exitPrice(),
                exitColor(trade),
                String.format(Locale.US,
                        "%s %.2f | %.2fR",
                        trade.isWin() ? "Gain" : "Stop",
                        trade.exitPrice(),
                        trade.rMultiple()));
    }

    private static String operationLabel(long time, double price, String color, String label) {
        return String.format(Locale.US,
                "{time:%d,price:%.5f,color:'%s',label:'%s'}",
                time,
                price,
                color,
                escapeJs(label));
    }

    private static long labelTime(String label) {
        int start = label.indexOf("time:") + 5;
        int end = label.indexOf(',', start);
        return Long.parseLong(label.substring(start, end));
    }

    private static String tradeVisibleRange(List<TradeResult> trades) {
        if (trades.isEmpty()) {
            return "null";
        }
        long first = trades.stream()
                .mapToLong(trade -> Math.min(epochSecond(trade.entryTime()), epochSecond(trade.exitTime())))
                .min()
                .orElse(0L);
        long last = trades.stream()
                .mapToLong(trade -> Math.max(epochSecond(trade.entryTime()), epochSecond(trade.exitTime())))
                .max()
                .orElse(0L);
        long padding = Math.max(1800L, (last - first) / 4);
        return String.format(Locale.US, "{from:%d,to:%d}", first - padding, last + padding);
    }

    private static long markerTime(String marker) {
        int start = marker.indexOf("time:") + 5;
        int end = marker.indexOf(',', start);
        return Long.parseLong(marker.substring(start, end));
    }

    private static String title(BarSeries series, List<TradeResult> trades) {
        double winRate = trades.isEmpty()
                ? 0.0
                : trades.stream().filter(TradeResult::isWin).count() / (double) trades.size();
        double avgR = trades.stream().mapToDouble(TradeResult::rMultiple).average().orElse(0.0);
        return String.format(Locale.US,
                "%s | Trades %d | Win %.1f%% | Avg R %.2f",
                series.getName(),
                trades.size(),
                winRate * 100.0,
                avgR);
    }

    private static void appendComma(StringBuilder builder) {
        if (builder.length() > 1) {
            builder.append(',');
        }
    }

    private static long epochSecond(ZonedDateTime time) {
        return time.toEpochSecond();
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeJs(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }

    @FunctionalInterface
    private interface IndicatorValue {
        org.ta4j.core.num.Num get(int index);
    }

    private enum TradePoint {
        LONG_ENTRY {
            @Override
            boolean includes(TradeResult trade) {
                return trade.direction() == Direction.LONG;
            }

            @Override
            long time(TradeResult trade) {
                return epochSecond(trade.entryTime());
            }

            @Override
            double price(TradeResult trade) {
                return trade.entryPrice();
            }
        },
        SHORT_ENTRY {
            @Override
            boolean includes(TradeResult trade) {
                return trade.direction() == Direction.SHORT;
            }

            @Override
            long time(TradeResult trade) {
                return epochSecond(trade.entryTime());
            }

            @Override
            double price(TradeResult trade) {
                return trade.entryPrice();
            }
        },
        STOP_GAIN {
            @Override
            boolean includes(TradeResult trade) {
                return trade.isWin();
            }

            @Override
            long time(TradeResult trade) {
                return epochSecond(trade.exitTime());
            }

            @Override
            double price(TradeResult trade) {
                return trade.exitPrice();
            }
        },
        STOP_LOSS {
            @Override
            boolean includes(TradeResult trade) {
                return !trade.isWin();
            }

            @Override
            long time(TradeResult trade) {
                return epochSecond(trade.exitTime());
            }

            @Override
            double price(TradeResult trade) {
                return trade.exitPrice();
            }
        };

        abstract boolean includes(TradeResult trade);

        abstract long time(TradeResult trade);

        abstract double price(TradeResult trade);
    }

    private static String exitColor(TradeResult trade) {
        return trade.isWin() ? "#00c853" : "#8b0000";
    }
}
