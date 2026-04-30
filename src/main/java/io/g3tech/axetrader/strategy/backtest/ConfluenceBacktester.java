package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.CandleWindow;
import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.EntrySignal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ConfluenceBacktester {

    private final ConfluenceEntryDetector entryDetector;
    private final BacktestSettings settings;

    public ConfluenceBacktester(ConfluenceEntryDetector entryDetector, int minimumCandles) {
        this(entryDetector, new BacktestSettings(minimumCandles, BacktestSettings.defaults().targetRiskMultiple()));
    }

    public ConfluenceBacktester(ConfluenceEntryDetector entryDetector, BacktestSettings settings) {
        this.entryDetector = entryDetector;
        this.settings = settings;
    }

    public BacktestResult run(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Backtest candles must not be empty");
        }

        var replay = new ArrayList<Candle>();
        var events = new ArrayList<BacktestEvent>();
        var trades = new ArrayList<BacktestTrade>();
        BacktestTrade openTrade = null;

        for (int i = 0; i < candles.size(); i++) {
            var candleIndex = i;
            var candle = candles.get(i);
            replay.add(candle);

            if (openTrade != null) {
                var closedTrade = closeTradeIfExitHit(openTrade, candleIndex, candle);
                if (closedTrade != openTrade) {
                    trades.add(closedTrade);
                    openTrade = null;
                }
            }

            if (openTrade != null || replay.size() < settings.minimumCandles()) {
                continue;
            }

            var window = new CandleWindow(replay);
            var signal = entryDetector.detect(window, window);
            if (signal.isPresent()) {
                events.add(new BacktestEvent(candleIndex, candle, signal.get()));
                openTrade = openTrade(candleIndex, signal.get());
            }
        }

        if (openTrade != null) {
            trades.add(closeAtEndOfData(openTrade, candles.size() - 1, candles.getLast()));
        }

        return new BacktestResult(candles.size(), settings.minimumCandles(), events, trades);
    }

    private BacktestTrade openTrade(int entryIndex, EntrySignal signal) {
        var targetPrice = targetPrice(signal);

        return new BacktestTrade(
                signal.direction(),
                entryIndex,
                signal.candleTime(),
                signal.entryPrice(),
                signal.stopLoss(),
                targetPrice,
                entryIndex,
                signal.candleTime(),
                signal.entryPrice(),
                ExitReason.END_OF_DATA,
                0,
                signal
        );
    }

    private BacktestTrade closeTradeIfExitHit(BacktestTrade trade, int candleIndex, Candle candle) {
        if (trade.direction() == Direction.LONG) {
            var stopHit = candle.low().compareTo(trade.stopLoss()) <= 0;
            var targetHit = candle.high().compareTo(trade.targetPrice()) >= 0;

            if (stopHit) {
                return closeTrade(trade, candleIndex, candle, trade.stopLoss(), ExitReason.STOP_LOSS_HIT);
            }

            if (targetHit) {
                return closeTrade(trade, candleIndex, candle, trade.targetPrice(), ExitReason.TARGET_HIT);
            }

            return trade;
        }

        var stopHit = candle.high().compareTo(trade.stopLoss()) >= 0;
        var targetHit = candle.low().compareTo(trade.targetPrice()) <= 0;

        if (stopHit) {
            return closeTrade(trade, candleIndex, candle, trade.stopLoss(), ExitReason.STOP_LOSS_HIT);
        }

        if (targetHit) {
            return closeTrade(trade, candleIndex, candle, trade.targetPrice(), ExitReason.TARGET_HIT);
        }

        return trade;
    }

    private BacktestTrade closeAtEndOfData(BacktestTrade trade, int candleIndex, Candle candle) {
        return closeTrade(trade, candleIndex, candle, candle.close(), ExitReason.END_OF_DATA);
    }

    private BacktestTrade closeTrade(
            BacktestTrade trade,
            int candleIndex,
            Candle candle,
            BigDecimal exitPrice,
            ExitReason exitReason
    ) {
        return new BacktestTrade(
                trade.direction(),
                trade.entryIndex(),
                trade.entryTime(),
                trade.entryPrice(),
                trade.stopLoss(),
                trade.targetPrice(),
                candleIndex,
                candle.openedAt(),
                exitPrice,
                exitReason,
                riskMultiple(trade, exitPrice),
                trade.signal()
        );
    }

    private BigDecimal targetPrice(EntrySignal signal) {
        var entryPrice = signal.entryPrice().doubleValue();
        var stopLoss = signal.stopLoss().doubleValue();
        var riskDistance = Math.abs(entryPrice - stopLoss);
        var targetDistance = riskDistance * settings.targetRiskMultiple();

        if (signal.direction() == Direction.LONG) {
            return BigDecimal.valueOf(entryPrice + targetDistance);
        }

        return BigDecimal.valueOf(entryPrice - targetDistance);
    }

    private double riskMultiple(BacktestTrade trade, BigDecimal exitPrice) {
        var entryPrice = trade.entryPrice().doubleValue();
        var exit = exitPrice.doubleValue();
        var riskDistance = Math.abs(entryPrice - trade.stopLoss().doubleValue());

        if (riskDistance == 0) {
            return 0;
        }

        if (trade.direction() == Direction.LONG) {
            return (exit - entryPrice) / riskDistance;
        }

        return (entryPrice - exit) / riskDistance;
    }
}
