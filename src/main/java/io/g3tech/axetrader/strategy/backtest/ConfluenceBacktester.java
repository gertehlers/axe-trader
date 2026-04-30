package io.g3tech.axetrader.strategy.backtest;

import io.g3tech.axetrader.strategy.Candle;
import io.g3tech.axetrader.strategy.CandleWindow;
import io.g3tech.axetrader.strategy.ConfluenceEntryDetector;
import io.g3tech.axetrader.strategy.Direction;
import io.g3tech.axetrader.strategy.EntrySignal;
import io.g3tech.axetrader.strategy.IndicatorCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ConfluenceBacktester {

    private static final List<Integer> FORWARD_HORIZONS = List.of(1, 3, 5, 10, 20);

    private final ConfluenceEntryDetector entryDetector;
    private final IndicatorCalculator indicatorCalculator;
    private final BacktestSettings settings;

    public ConfluenceBacktester(ConfluenceEntryDetector entryDetector, int minimumCandles) {
        this(entryDetector, new BacktestSettings(minimumCandles, BacktestSettings.defaults().targetRiskMultiple()));
    }

    public ConfluenceBacktester(ConfluenceEntryDetector entryDetector, BacktestSettings settings) {
        this(entryDetector, new IndicatorCalculator(), settings);
    }

    public ConfluenceBacktester(
            ConfluenceEntryDetector entryDetector,
            IndicatorCalculator indicatorCalculator,
            BacktestSettings settings
    ) {
        this.entryDetector = entryDetector;
        this.indicatorCalculator = indicatorCalculator;
        this.settings = settings;
    }

    public BacktestResult run(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Backtest candles must not be empty");
        }

        var replay = new ArrayList<Candle>();
        var events = new ArrayList<BacktestEvent>();
        var trades = new ArrayList<BacktestTrade>();
        var signalEvaluations = new ArrayList<SignalEvaluation>();
        var volatilityRegimeClassifier = VolatilityRegimeClassifier.from(candles, settings.minimumCandles(), indicatorCalculator);
        BacktestTrade openTrade = null;

        for (int i = 0; i < candles.size(); i++) {
            var candleIndex = i;
            var candle = candles.get(i);
            replay.add(candle);

            if (openTrade != null) {
                var closedTrade = closeTradeIfExitHit(openTrade, candleIndex, candle);
                if (closedTrade != openTrade) {
                    trades.add(closedTrade);
                    signalEvaluations.add(evaluate(closedTrade, candles, volatilityRegimeClassifier));
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
            var closedTrade = closeAtEndOfData(openTrade, candles.size() - 1, candles.getLast());
            trades.add(closedTrade);
            signalEvaluations.add(evaluate(closedTrade, candles, volatilityRegimeClassifier));
        }

        return new BacktestResult(candles.size(), settings.minimumCandles(), events, trades, signalEvaluations);
    }

    private BacktestTrade openTrade(int entryIndex, EntrySignal signal) {
        var stopLoss = stopLoss(signal);
        var targetPrice = targetPrice(signal, stopLoss);

        return new BacktestTrade(
                signal.direction(),
                entryIndex,
                signal.candleTime(),
                signal.entryPrice(),
                stopLoss,
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

    private BigDecimal stopLoss(EntrySignal signal) {
        var stopDistance = signal.indicators().atr14() * settings.stopAtrMultiple();
        if (signal.direction() == Direction.LONG) {
            return BigDecimal.valueOf(signal.entryPrice().doubleValue() - stopDistance);
        }

        return BigDecimal.valueOf(signal.entryPrice().doubleValue() + stopDistance);
    }

    private BigDecimal targetPrice(EntrySignal signal, BigDecimal stopLoss) {
        var entryPrice = signal.entryPrice().doubleValue();
        var riskDistance = Math.abs(entryPrice - stopLoss.doubleValue());
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

    private SignalEvaluation evaluate(
            BacktestTrade trade,
            List<Candle> candles,
            VolatilityRegimeClassifier volatilityRegimeClassifier
    ) {
        var signal = trade.signal();
        var mfe = 0.0;
        var mae = 0.0;

        for (int i = trade.entryIndex(); i <= trade.exitIndex(); i++) {
            var candle = candles.get(i);
            if (trade.direction() == Direction.LONG) {
                mfe = Math.max(mfe, candle.high().doubleValue() - trade.entryPrice().doubleValue());
                mae = Math.max(mae, trade.entryPrice().doubleValue() - candle.low().doubleValue());
            } else {
                mfe = Math.max(mfe, trade.entryPrice().doubleValue() - candle.low().doubleValue());
                mae = Math.max(mae, candle.high().doubleValue() - trade.entryPrice().doubleValue());
            }
        }

        return new SignalEvaluation(
                trade.entryIndex(),
                signal,
                outcome(trade),
                volatilityRegime(signal, volatilityRegimeClassifier),
                trendRegime(signal),
                trade.exitIndex() - trade.entryIndex(),
                mfe,
                mae,
                forwardMovements(trade, candles)
        );
    }

    private SignalOutcome outcome(BacktestTrade trade) {
        return switch (trade.exitReason()) {
            case TARGET_HIT -> SignalOutcome.TARGET_HIT;
            case STOP_LOSS_HIT -> SignalOutcome.STOP_LOSS_HIT;
            case END_OF_DATA -> SignalOutcome.END_OF_DATA;
        };
    }

    private VolatilityRegime volatilityRegime(EntrySignal signal, VolatilityRegimeClassifier volatilityRegimeClassifier) {
        var entryPrice = signal.entryPrice().doubleValue();
        if (entryPrice == 0) {
            return VolatilityRegime.NORMAL;
        }

        var atrPercent = signal.indicators().atr14() / entryPrice;
        return volatilityRegimeClassifier.classify(atrPercent);
    }

    private TrendRegime trendRegime(EntrySignal signal) {
        var indicators = signal.indicators();
        if (indicators.adx14() >= 20 && indicators.ema20() > indicators.ema50() && indicators.ema20Slope() > 0) {
            return TrendRegime.TRENDING_UP;
        }
        if (indicators.adx14() >= 20 && indicators.ema20() < indicators.ema50() && indicators.ema20Slope() < 0) {
            return TrendRegime.TRENDING_DOWN;
        }

        return TrendRegime.RANGING;
    }

    private List<ForwardMovement> forwardMovements(BacktestTrade trade, List<Candle> candles) {
        return FORWARD_HORIZONS.stream()
                .map(horizon -> forwardMovement(trade, candles, horizon))
                .toList();
    }

    private ForwardMovement forwardMovement(BacktestTrade trade, List<Candle> candles, int horizon) {
        var endIndex = Math.min(candles.size() - 1, trade.entryIndex() + horizon);
        var entry = trade.entryPrice().doubleValue();
        var favorable = 0.0;
        var adverse = 0.0;

        for (int i = trade.entryIndex(); i <= endIndex; i++) {
            var candle = candles.get(i);
            if (trade.direction() == Direction.LONG) {
                favorable = Math.max(favorable, candle.highValue() - entry);
                adverse = Math.max(adverse, entry - candle.lowValue());
            } else {
                favorable = Math.max(favorable, entry - candle.lowValue());
                adverse = Math.max(adverse, candle.highValue() - entry);
            }
        }

        var close = candles.get(endIndex).closeValue();
        var closeMove = trade.direction() == Direction.LONG ? close - entry : entry - close;
        return new ForwardMovement(horizon, favorable, adverse, closeMove);
    }
}
