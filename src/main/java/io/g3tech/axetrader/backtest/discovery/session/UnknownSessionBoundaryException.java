package io.g3tech.axetrader.backtest.discovery.session;

import java.time.Instant;

public class UnknownSessionBoundaryException extends RuntimeException {

    public UnknownSessionBoundaryException(Instant barTime) {
        super("No known trading-session boundary after " + barTime);
    }
}
