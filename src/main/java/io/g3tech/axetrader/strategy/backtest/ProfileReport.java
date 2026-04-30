package io.g3tech.axetrader.strategy.backtest;

import java.util.List;

public record ProfileReport(
        List<String> strongestSignalTypes,
        List<String> weakestSignalTypes
) {
}
