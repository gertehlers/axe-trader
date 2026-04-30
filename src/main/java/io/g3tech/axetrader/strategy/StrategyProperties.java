package io.g3tech.axetrader.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "axe-trader.strategy")
public record StrategyProperties(
        int minimumCandles,
        int structureLookback,
        int minimumScore,
        double minimumAdx,
        double bullishCloseLocation,
        double bearishCloseLocation,
        double stopAtrMultiple,
        double maxAtrExtensionFromFastEma,
        Indicators indicators
) {

    public StrategyProperties {
        if (minimumCandles == 0) {
            minimumCandles = 60;
        }
        if (structureLookback == 0) {
            structureLookback = 10;
        }
        if (minimumScore == 0) {
            minimumScore = 8;
        }
        if (minimumAdx == 0) {
            minimumAdx = 20.0;
        }
        if (bullishCloseLocation == 0) {
            bullishCloseLocation = 0.70;
        }
        if (bearishCloseLocation == 0) {
            bearishCloseLocation = 0.30;
        }
        if (stopAtrMultiple == 0) {
            stopAtrMultiple = 1.5;
        }
        if (maxAtrExtensionFromFastEma == 0) {
            maxAtrExtensionFromFastEma = 10.0;
        }
        if (indicators == null) {
            indicators = Indicators.defaults();
        }
    }

    public ConfluenceSettings toConfluenceSettings() {
        return new ConfluenceSettings(
                minimumCandles,
                structureLookback,
                minimumScore,
                minimumAdx,
                bullishCloseLocation,
                bearishCloseLocation,
                stopAtrMultiple,
                maxAtrExtensionFromFastEma
        );
    }

    public IndicatorSettings toIndicatorSettings() {
        return new IndicatorSettings(
                indicators.fastEmaPeriod(),
                indicators.slowEmaPeriod(),
                indicators.rsiPeriod(),
                indicators.atrPeriod(),
                indicators.adxPeriod()
        );
    }

    public record Indicators(
            int fastEmaPeriod,
            int slowEmaPeriod,
            int rsiPeriod,
            int atrPeriod,
            int adxPeriod
    ) {

        public Indicators {
            if (fastEmaPeriod == 0) {
                fastEmaPeriod = 20;
            }
            if (slowEmaPeriod == 0) {
                slowEmaPeriod = 50;
            }
            if (rsiPeriod == 0) {
                rsiPeriod = 14;
            }
            if (atrPeriod == 0) {
                atrPeriod = 14;
            }
            if (adxPeriod == 0) {
                adxPeriod = 14;
            }
        }

        public static Indicators defaults() {
            return new Indicators(20, 50, 14, 14, 14);
        }
    }
}
