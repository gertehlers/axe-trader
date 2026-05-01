package io.g3tech.axetrader.strategy;

public record IndicatorSnapshot(
        double ema20,
        double ema50,
        double ema20Slope,
        double rsi14,
        double atr14,
        double adx14,
        double plusDi14,
        double minusDi14,
        IndicatorExtensions extensions
) {

    public IndicatorSnapshot(
            double ema20,
            double ema50,
            double ema20Slope,
            double rsi14,
            double atr14,
            double adx14,
            double plusDi14,
            double minusDi14
    ) {
        this(
                ema20,
                ema50,
                ema20Slope,
                rsi14,
                atr14,
                adx14,
                plusDi14,
                minusDi14,
                IndicatorExtensions.empty()
        );
    }

    public IndicatorSnapshot {
        if (extensions == null) {
            extensions = IndicatorExtensions.empty();
        }
    }

    public double vwap() {
        return extensions.vwap().vwap();
    }

    public double distanceFromVWAP() {
        return extensions.vwap().distanceFromVWAP();
    }

    public double normalizedVWAPDistance() {
        return extensions.vwap().normalizedVWAPDistance();
    }

    public double bodyToRangeRatio() {
        return extensions.candleStrength().bodyToRangeRatio();
    }

    public double closeLocation() {
        return extensions.candleStrength().closeLocation();
    }

    public double recentHigh() {
        return extensions.structure().recentHigh();
    }

    public double recentLow() {
        return extensions.structure().recentLow();
    }

    public boolean breaksAboveStructure() {
        return extensions.structure().breaksAboveStructure();
    }

    public boolean breaksBelowStructure() {
        return extensions.structure().breaksBelowStructure();
    }

    public double normalizedEmaSlope() {
        return extensions.normalizedEmaSlope();
    }

    public record IndicatorExtensions(
            VWAPMetrics vwap,
            CandleStrength candleStrength,
            StructureBreak structure,
            double normalizedEmaSlope
    ) {

        public IndicatorExtensions {
            if (vwap == null) {
                vwap = VWAPMetrics.empty();
            }
            if (candleStrength == null) {
                candleStrength = CandleStrength.empty();
            }
            if (structure == null) {
                structure = StructureBreak.empty();
            }
        }

        public static IndicatorExtensions empty() {
            return new IndicatorExtensions(VWAPMetrics.empty(), CandleStrength.empty(), StructureBreak.empty(), 0);
        }
    }

    public record VWAPMetrics(
            double vwap,
            double distanceFromVWAP,
            double normalizedVWAPDistance
    ) {

        public static VWAPMetrics empty() {
            return new VWAPMetrics(0, 0, 0);
        }
    }

    public record CandleStrength(
            double bodyToRangeRatio,
            double closeLocation
    ) {

        public static CandleStrength empty() {
            return new CandleStrength(0, 0.5);
        }
    }

    public record StructureBreak(
            double recentHigh,
            double recentLow,
            boolean breaksAboveStructure,
            boolean breaksBelowStructure
    ) {

        public static StructureBreak empty() {
            return new StructureBreak(0, 0, false, false);
        }
    }
}
