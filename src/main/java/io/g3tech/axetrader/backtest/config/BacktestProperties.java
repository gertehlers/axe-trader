package io.g3tech.axetrader.backtest.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "backtest")
public class BacktestProperties {

    private String epic;
    private int limit;
    private int timeframeMinutes;
    private Strategy strategy = new Strategy();
    private Contract contract = new Contract();

    public String getEpic() {
        return epic;
    }

    public void setEpic(String epic) {
        this.epic = epic;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getTimeframeMinutes() {
        return timeframeMinutes;
    }

    public void setTimeframeMinutes(int timeframeMinutes) {
        this.timeframeMinutes = timeframeMinutes;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    /** Fail fast on a mis-specified exit ladder rather than silently mis-sizing every trade. */
    @PostConstruct
    void validateExitLadder() {
        if (getStrategy() != null) {
            getStrategy().getExit().validate();
        }
    }

    /**
     * How an index-point move translates to money, so backtest expectancy can be read in $ instead
     * of raw S&amp;P points. {@code valuePerPoint} is the account's P&amp;L per 1.0 index point per 1
     * unit/contract (Capital.com's standard US500 CFD is ~$1/pt); default 1.0 keeps output in points.
     */
    public static class Contract {
        private double valuePerPoint = 1.0;

        public double getValuePerPoint() {
            return valuePerPoint;
        }

        public void setValuePerPoint(double valuePerPoint) {
            this.valuePerPoint = valuePerPoint;
        }
    }

    public static class Strategy {
        private int rsiPeriod;
        private int rsiSmoothPeriod;
        private int bbPeriod;
        private double bbMultiplier;
        private int emaPeriod;
        private int atrPeriod;
        private double rsiOversold;
        private double rsiOverbought;
        private double stopAtrMultiple;
        private double targetAtrMultiple;
        private int maxHoldingBars;         // force-exit after N bars if neither stop nor target hit (0 = disabled)
        private int trendEmaPeriod;         // hard directional gate: long only above / short only below this EMA (0 = disabled)
        private double trendEmaMaxAtr;      // proximity ceiling: only enter within this many ATR of the trend EMA (0 = disabled)

        // Scale-out exit ladder. Empty (the default) means the current all-or-nothing behaviour:
        // one tier at 100% using targetAtrMultiple, no ratchet.
        private Exit exit = new Exit();

        // Confluence (5-pillar voting)
        private int confluenceThreshold;     // votes required to enter
        private double proximityAtrMultiple; // "near" a swing level, in ATR units (pillar 3)
        private int swingLookbackBars;       // backward window for support/resistance & structure (pillars 3 & 4)
        private int volumeSmaPeriod;         // volume baseline (pillar 5)
        private boolean enableCandles;
        private boolean enableSupportResistance;
        private boolean enableStructure;
        private boolean enableVolumeTrend;
        private boolean enableLong = true;   // take LONG entries
        private boolean enableShort = true;  // take SHORT entries (off for upward-drift instruments where shorts are crash-only)

        public int getRsiPeriod() {
            return rsiPeriod;
        }

        public void setRsiPeriod(int rsiPeriod) {
            this.rsiPeriod = rsiPeriod;
        }

        public int getRsiSmoothPeriod() {
            return rsiSmoothPeriod;
        }

        public void setRsiSmoothPeriod(int rsiSmoothPeriod) {
            this.rsiSmoothPeriod = rsiSmoothPeriod;
        }

        public int getBbPeriod() {
            return bbPeriod;
        }

        public void setBbPeriod(int bbPeriod) {
            this.bbPeriod = bbPeriod;
        }

        public double getBbMultiplier() {
            return bbMultiplier;
        }

        public void setBbMultiplier(double bbMultiplier) {
            this.bbMultiplier = bbMultiplier;
        }

        public int getEmaPeriod() {
            return emaPeriod;
        }

        public void setEmaPeriod(int emaPeriod) {
            this.emaPeriod = emaPeriod;
        }

        public int getAtrPeriod() {
            return atrPeriod;
        }

        public void setAtrPeriod(int atrPeriod) {
            this.atrPeriod = atrPeriod;
        }

        public double getRsiOversold() {
            return rsiOversold;
        }

        public void setRsiOversold(double rsiOversold) {
            this.rsiOversold = rsiOversold;
        }

        public double getRsiOverbought() {
            return rsiOverbought;
        }

        public void setRsiOverbought(double rsiOverbought) {
            this.rsiOverbought = rsiOverbought;
        }

        public double getStopAtrMultiple() {
            return stopAtrMultiple;
        }

        public void setStopAtrMultiple(double stopAtrMultiple) {
            this.stopAtrMultiple = stopAtrMultiple;
        }

        public double getTargetAtrMultiple() {
            return targetAtrMultiple;
        }

        public void setTargetAtrMultiple(double targetAtrMultiple) {
            this.targetAtrMultiple = targetAtrMultiple;
        }

        public int getMaxHoldingBars() {
            return maxHoldingBars;
        }

        public void setMaxHoldingBars(int maxHoldingBars) {
            this.maxHoldingBars = maxHoldingBars;
        }

        public int getTrendEmaPeriod() {
            return trendEmaPeriod;
        }

        public void setTrendEmaPeriod(int trendEmaPeriod) {
            this.trendEmaPeriod = trendEmaPeriod;
        }

        public double getTrendEmaMaxAtr() {
            return trendEmaMaxAtr;
        }

        public void setTrendEmaMaxAtr(double trendEmaMaxAtr) {
            this.trendEmaMaxAtr = trendEmaMaxAtr;
        }

        public int getConfluenceThreshold() {
            return confluenceThreshold;
        }

        public void setConfluenceThreshold(int confluenceThreshold) {
            this.confluenceThreshold = confluenceThreshold;
        }

        public double getProximityAtrMultiple() {
            return proximityAtrMultiple;
        }

        public void setProximityAtrMultiple(double proximityAtrMultiple) {
            this.proximityAtrMultiple = proximityAtrMultiple;
        }

        public int getSwingLookbackBars() {
            return swingLookbackBars;
        }

        public void setSwingLookbackBars(int swingLookbackBars) {
            this.swingLookbackBars = swingLookbackBars;
        }

        public int getVolumeSmaPeriod() {
            return volumeSmaPeriod;
        }

        public void setVolumeSmaPeriod(int volumeSmaPeriod) {
            this.volumeSmaPeriod = volumeSmaPeriod;
        }

        public boolean isEnableCandles() {
            return enableCandles;
        }

        public void setEnableCandles(boolean enableCandles) {
            this.enableCandles = enableCandles;
        }

        public boolean isEnableSupportResistance() {
            return enableSupportResistance;
        }

        public void setEnableSupportResistance(boolean enableSupportResistance) {
            this.enableSupportResistance = enableSupportResistance;
        }

        public boolean isEnableStructure() {
            return enableStructure;
        }

        public void setEnableStructure(boolean enableStructure) {
            this.enableStructure = enableStructure;
        }

        public boolean isEnableVolumeTrend() {
            return enableVolumeTrend;
        }

        public void setEnableVolumeTrend(boolean enableVolumeTrend) {
            this.enableVolumeTrend = enableVolumeTrend;
        }

        public boolean isEnableLong() {
            return enableLong;
        }

        public void setEnableLong(boolean enableLong) {
            this.enableLong = enableLong;
        }

        public boolean isEnableShort() {
            return enableShort;
        }

        public void setEnableShort(boolean enableShort) {
            this.enableShort = enableShort;
        }

        public Exit getExit() {
            return exit;
        }

        public void setExit(Exit exit) {
            this.exit = exit == null ? new Exit() : exit;
        }

        /** The scale-out ladder and its ratchet rule. */
        public static class Exit {
            private List<ExitTier> tiers = List.of();
            private Ratchet ratchet = Ratchet.NONE;

            public List<ExitTier> getTiers() {
                return tiers;
            }

            public void setTiers(List<ExitTier> tiers) {
                this.tiers = tiers == null ? List.of() : List.copyOf(tiers);
            }

            public Ratchet getRatchet() {
                return ratchet;
            }

            public void setRatchet(Ratchet ratchet) {
                this.ratchet = ratchet == null ? Ratchet.NONE : ratchet;
            }

            /**
             * Fails fast on a ladder that would mis-size every trade. Deliberately does NOT
             * renormalise fractions: silently "fixing" the config would bias every pnl figure in a
             * sweep with nothing on screen to show it happened.
             */
            public void validate() {
                if (tiers.isEmpty()) {
                    return;
                }
                double sum = 0.0;
                double previousTarget = Double.NEGATIVE_INFINITY;
                for (ExitTier tier : tiers) {
                    if (tier.getFraction() <= 0.0) {
                        throw new IllegalStateException(
                                "exit tier fraction must be positive, got " + tier.getFraction());
                    }
                    if (tier.getTargetAtrMultiple() <= previousTarget) {
                        throw new IllegalStateException(
                                "exit tiers must be in ascending target order, got "
                                        + tier.getTargetAtrMultiple() + " after " + previousTarget);
                    }
                    previousTarget = tier.getTargetAtrMultiple();
                    sum += tier.getFraction();
                }
                if (Math.abs(sum - 1.0) > 1e-6) {
                    throw new IllegalStateException(
                            "exit tier fractions must sum to 1.0, got " + sum);
                }
            }
        }

        /** One rung: bank {@code fraction} of the position at {@code targetAtrMultiple} ATR. */
        public static class ExitTier {
            private double fraction;
            private double targetAtrMultiple;

            public double getFraction() {
                return fraction;
            }

            public void setFraction(double fraction) {
                this.fraction = fraction;
            }

            public double getTargetAtrMultiple() {
                return targetAtrMultiple;
            }

            public void setTargetAtrMultiple(double targetAtrMultiple) {
                this.targetAtrMultiple = targetAtrMultiple;
            }
        }
    }
}
