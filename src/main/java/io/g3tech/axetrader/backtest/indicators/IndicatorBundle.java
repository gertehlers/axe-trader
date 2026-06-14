package io.g3tech.axetrader.backtest.indicators;

import io.g3tech.axetrader.backtest.config.BacktestProperties;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

public class IndicatorBundle {

    public final BarSeries series;
    public final ClosePriceIndicator closePrice;
    public final EMAIndicator ema;
    public final RSIIndicator rsi;
    public final SMAIndicator smoothRsi;
    public final BollingerBandsMiddleIndicator bbMiddle;
    public final BollingerBandsUpperIndicator bbUpper;
    public final BollingerBandsLowerIndicator bbLower;
    public final ATRIndicator atr;

    private IndicatorBundle(
            BarSeries series,
            ClosePriceIndicator closePrice,
            EMAIndicator ema,
            RSIIndicator rsi,
            SMAIndicator smoothRsi,
            BollingerBandsMiddleIndicator bbMiddle,
            BollingerBandsUpperIndicator bbUpper,
            BollingerBandsLowerIndicator bbLower,
            ATRIndicator atr) {
        this.series = series;
        this.closePrice = closePrice;
        this.ema = ema;
        this.rsi = rsi;
        this.smoothRsi = smoothRsi;
        this.bbMiddle = bbMiddle;
        this.bbUpper = bbUpper;
        this.bbLower = bbLower;
        this.atr = atr;
    }

    public static IndicatorBundle from(BarSeries series, BacktestProperties.Strategy config) {
        var closePrice = new ClosePriceIndicator(series);
        var ema = new EMAIndicator(closePrice, config.getEmaPeriod());
        var rsi = new RSIIndicator(closePrice, config.getRsiPeriod());
        var smoothRsi = new SMAIndicator(rsi, config.getRsiSmoothPeriod());

        var bbSma = new SMAIndicator(closePrice, config.getBbPeriod());
        var bbMiddle = new BollingerBandsMiddleIndicator(bbSma);
        var standardDeviation = new StandardDeviationIndicator(closePrice, config.getBbPeriod());
        var multiplier = series.numFactory().numOf(config.getBbMultiplier());
        var bbUpper = new BollingerBandsUpperIndicator(bbMiddle, standardDeviation, multiplier);
        var bbLower = new BollingerBandsLowerIndicator(bbMiddle, standardDeviation, multiplier);

        var atr = new ATRIndicator(series, config.getAtrPeriod());

        return new IndicatorBundle(
                series,
                closePrice,
                ema,
                rsi,
                smoothRsi,
                bbMiddle,
                bbUpper,
                bbLower,
                atr);
    }
}
