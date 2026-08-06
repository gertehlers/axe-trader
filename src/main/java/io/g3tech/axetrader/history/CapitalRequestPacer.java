package io.g3tech.axetrader.history;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Component
public final class CapitalRequestPacer {

    private final long intervalNanos;
    private final NanoClock clock;
    private final Sleeper sleeper;
    private long nextPermitNanos;
    private boolean used;

    @Autowired
    public CapitalRequestPacer(@Value("${axe-trader.history-import.requests-per-second:5}") int requestsPerSecond) {
        this(requestsPerSecond, System::nanoTime, duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pacing Capital requests", exception);
            }
        });
    }

    CapitalRequestPacer(int requestsPerSecond, NanoClock clock, Sleeper sleeper) {
        if (requestsPerSecond < 1 || requestsPerSecond > 5) {
            throw new IllegalArgumentException("Capital request rate must be between 1 and 5 requests per second");
        }
        this.intervalNanos = Duration.ofSeconds(1).toNanos() / requestsPerSecond;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    synchronized void acquire() {
        long now = clock.nanoTime();
        if (used && now < nextPermitNanos) {
            sleeper.sleep(Duration.ofNanos(nextPermitNanos - now));
            now = clock.nanoTime();
        }
        used = true;
        nextPermitNanos = now + intervalNanos;
    }

    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration);
    }
}
