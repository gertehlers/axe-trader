package io.g3tech.axetrader.backtest.discovery.session;

import java.time.Instant;
import java.util.Optional;

public interface TradingSessionCalendar {

    Optional<SessionBoundary> boundaryAfter(Instant barTime);

    int minutesToClose(Instant barTime);
}
