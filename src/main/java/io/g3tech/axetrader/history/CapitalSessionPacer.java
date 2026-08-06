package io.g3tech.axetrader.history;

import org.springframework.stereotype.Component;

@Component
public final class CapitalSessionPacer {

    private final CapitalRequestPacer delegate;

    public CapitalSessionPacer() {
        this.delegate = new CapitalRequestPacer(1);
    }

    CapitalSessionPacer(CapitalRequestPacer.NanoClock clock, CapitalRequestPacer.Sleeper sleeper) {
        this.delegate = new CapitalRequestPacer(1, clock, sleeper);
    }

    void acquire() {
        delegate.acquire();
    }
}
