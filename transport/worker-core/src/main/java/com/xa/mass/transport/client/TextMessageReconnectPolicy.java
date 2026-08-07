package com.xa.mass.transport.client;

import java.time.Duration;
import java.util.Objects;

/**
 * Fixed reconnect budget for one text-message endpoint.
 */
public final class TextMessageReconnectPolicy {

    private final int maxUnstableAttempts;
    private final Duration reconnectInterval;
    private final Duration stableConnectionDuration;

    private TextMessageReconnectPolicy(
            int maxUnstableAttempts,
            Duration reconnectInterval,
            Duration stableConnectionDuration
    ) {
        if (maxUnstableAttempts <= 0) {
            throw new IllegalArgumentException(
                    "maxUnstableAttempts must be positive"
            );
        }
        this.maxUnstableAttempts = maxUnstableAttempts;
        this.reconnectInterval = requirePositive(
                reconnectInterval,
                "reconnectInterval"
        );
        this.stableConnectionDuration = requirePositive(
                stableConnectionDuration,
                "stableConnectionDuration"
        );
    }

    public static TextMessageReconnectPolicy of(
            int maxUnstableAttempts,
            Duration reconnectInterval,
            Duration stableConnectionDuration
    ) {
        return new TextMessageReconnectPolicy(
                maxUnstableAttempts,
                reconnectInterval,
                stableConnectionDuration
        );
    }

    public int maxUnstableAttempts() {
        return maxUnstableAttempts;
    }

    public Duration reconnectInterval() {
        return reconnectInterval;
    }

    public Duration stableConnectionDuration() {
        return stableConnectionDuration;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
