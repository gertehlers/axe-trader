package io.g3tech.axetrader.strategy;

public record ConfluenceSettings(
        int minimumCandles,
        int structureLookback,
        int minimumScore,
        double minimumAdx,
        double bullishCloseLocation,
        double bearishCloseLocation,
        double stopAtrMultiple,
        double maxAtrExtensionFromEma20
) {

    public static ConfluenceSettings defaults() {
        return new ConfluenceSettings(
                60,
                10,
                8,
                20.0,
                0.70,
                0.30,
                1.5,
                1.25
        );
    }
}
