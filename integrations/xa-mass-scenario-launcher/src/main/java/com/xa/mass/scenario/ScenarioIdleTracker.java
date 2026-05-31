package com.xa.mass.scenario;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

final class ScenarioIdleTracker {
    private final Clock clock;
    private final AtomicLong lastActivityMillis;

    ScenarioIdleTracker() {
        this(Clock.systemUTC());
    }

    ScenarioIdleTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.lastActivityMillis = new AtomicLong(clock.millis());
    }

    void markActivity() {
        lastActivityMillis.set(clock.millis());
    }

    long idleMillis() {
        return Math.max(0L, clock.millis() - lastActivityMillis.get());
    }

    boolean isIdleFor(long timeoutMs) {
        return timeoutMs > 0 && idleMillis() >= timeoutMs;
    }
}
