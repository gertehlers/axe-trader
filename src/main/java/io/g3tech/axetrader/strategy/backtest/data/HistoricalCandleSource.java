package io.g3tech.axetrader.strategy.backtest.data;

import io.g3tech.axetrader.strategy.Candle;

import java.util.List;

public interface HistoricalCandleSource {

    List<Candle> load(HistoricalPriceRequest request);
}
