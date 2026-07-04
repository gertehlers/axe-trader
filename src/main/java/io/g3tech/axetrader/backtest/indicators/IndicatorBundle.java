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
import org.ta4j.core.indicators.candles.BearishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BearishHaramiIndicator;
import org.ta4j.core.indicators.candles.BullishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BullishHaramiIndicator;
import org.ta4j.core.indicators.candles.HammerIndicator;
import org.ta4j.core.indicators.candles.ShootingStarIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

public class IndicatorBundle {

    public final BarSeries series;
    public final ClosePriceIndicator closePrice;
    public final EMAIndicator ema;
    /** Higher-timeframe trend gate EMA; null when {@code trend-ema-period} is 0 (gate disabled). */
    public final EMAIndicator trendEma;
    public final RSIIndicator rsi;
    public final SMAIndicator smoothRsi;
    public final BollingerBandsMiddleIndicator bbMiddle;
    public final BollingerBandsUpperIndicator bbUpper;
    public final BollingerBandsLowerIndicator bbLower;
    public final ATRIndicator atr;

    // Pillar 2 — candlestick patterns
    public final BullishEngulfingIndicator bullishEngulfing;
    public final BearishEngulfingIndicator bearishEngulfing;
    public final BullishHaramiIndicator bullishHarami;
    public final BearishHaramiIndicator bearishHarami;
    public final HammerIndicator hammer;
    public final ShootingStarIndicator shootingStar;

    // Pillar 5 — volume confirmation
    public final VolumeIndicator volume;
    public final SMAIndicator volumeSma;

    private IndicatorBundle(Builder b) {
        this.series = b.series;
        this.closePrice = b.closePrice;
        this.ema = b.ema;
        this.trendEma = b.trendEma;
        this.rsi = b.rsi;
        this.smoothRsi = b.smoothRsi;
        this.bbMiddle = b.bbMiddle;
        this.bbUpper = b.bbUpper;
        this.bbLower = b.bbLower;
        this.atr = b.atr;
        this.bullishEngulfing = b.bullishEngulfing;
        this.bearishEngulfing = b.bearishEngulfing;
        this.bullishHarami = b.bullishHarami;
        this.bearishHarami = b.bearishHarami;
        this.hammer = b.hammer;
        this.shootingStar = b.shootingStar;
        this.volume = b.volume;
        this.volumeSma = b.volumeSma;
    }

    public static IndicatorBundle from(BarSeries series, BacktestProperties.Strategy config) {
        Builder b = new Builder();
        b.series = series;
        b.closePrice = new ClosePriceIndicator(series);
        b.ema = new EMAIndicator(b.closePrice, config.getEmaPeriod());
        b.trendEma = config.getTrendEmaPeriod() > 0
                ? new EMAIndicator(b.closePrice, config.getTrendEmaPeriod())
                : null;
        b.rsi = new RSIIndicator(b.closePrice, config.getRsiPeriod());
        b.smoothRsi = new SMAIndicator(b.rsi, config.getRsiSmoothPeriod());

        var bbSma = new SMAIndicator(b.closePrice, config.getBbPeriod());
        b.bbMiddle = new BollingerBandsMiddleIndicator(bbSma);
        var standardDeviation = new StandardDeviationIndicator(b.closePrice, config.getBbPeriod());
        var multiplier = series.numFactory().numOf(config.getBbMultiplier());
        b.bbUpper = new BollingerBandsUpperIndicator(b.bbMiddle, standardDeviation, multiplier);
        b.bbLower = new BollingerBandsLowerIndicator(b.bbMiddle, standardDeviation, multiplier);

        b.atr = new ATRIndicator(series, config.getAtrPeriod());

        b.bullishEngulfing = new BullishEngulfingIndicator(series);
        b.bearishEngulfing = new BearishEngulfingIndicator(series);
        b.bullishHarami = new BullishHaramiIndicator(series);
        b.bearishHarami = new BearishHaramiIndicator(series);
        b.hammer = new HammerIndicator(series);
        b.shootingStar = new ShootingStarIndicator(series);

        b.volume = new VolumeIndicator(series);
        b.volumeSma = new SMAIndicator(b.volume, config.getVolumeSmaPeriod());

        return new IndicatorBundle(b);
    }

    private static final class Builder {
        private BarSeries series;
        private ClosePriceIndicator closePrice;
        private EMAIndicator ema;
        private EMAIndicator trendEma;
        private RSIIndicator rsi;
        private SMAIndicator smoothRsi;
        private BollingerBandsMiddleIndicator bbMiddle;
        private BollingerBandsUpperIndicator bbUpper;
        private BollingerBandsLowerIndicator bbLower;
        private ATRIndicator atr;
        private BullishEngulfingIndicator bullishEngulfing;
        private BearishEngulfingIndicator bearishEngulfing;
        private BullishHaramiIndicator bullishHarami;
        private BearishHaramiIndicator bearishHarami;
        private HammerIndicator hammer;
        private ShootingStarIndicator shootingStar;
        private VolumeIndicator volume;
        private SMAIndicator volumeSma;
    }
}
