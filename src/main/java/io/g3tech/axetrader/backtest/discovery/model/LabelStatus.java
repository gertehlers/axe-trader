package io.g3tech.axetrader.backtest.discovery.model;

/** Completeness of an offline-only forward-path label. */
public enum LabelStatus {
    COMPLETE_48_BARS,
    TRADING_CLOSE,
    INCOMPLETE_GAP,
    NO_EXECUTABLE_NEXT_BAR
}
