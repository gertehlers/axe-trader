package io.g3tech.axetrader.backtest.discovery.session;

import java.time.Instant;

public record SessionBoundary(Instant finalExecutableBar, Instant nextOpen) {
}
