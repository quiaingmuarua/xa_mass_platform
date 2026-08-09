package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry budgets inside one Worker run. Preparation attempts precede one bound,
 * reconnecting text-message endpoint. Exhausting either applicable budget ends
 * the run; a host may explicitly start the Worker again.
 */
public final class WorkerRetryPolicy {

    private static final WorkerRetryPolicy DEFAULT = of(
            10,
            Duration.ofSeconds(1),
            TextMessageReconnectPolicy.of(
                    20,
                    Duration.ofMillis(500),
                    Duration.ofSeconds(10)
            )
    );

    private final int maxPrepareAttempts;
    private final Duration prepareRetryInterval;
    private final TextMessageReconnectPolicy connectionPolicy;

    private WorkerRetryPolicy(
            int maxPrepareAttempts,
            Duration prepareRetryInterval,
            TextMessageReconnectPolicy connectionPolicy
    ) {
        if (maxPrepareAttempts <= 0) {
            throw new IllegalArgumentException(
                    "maxPrepareAttempts must be positive"
            );
        }
        this.maxPrepareAttempts = maxPrepareAttempts;
        this.prepareRetryInterval = requirePositive(
                prepareRetryInterval,
                "prepareRetryInterval"
        );
        this.connectionPolicy = Objects.requireNonNull(
                connectionPolicy,
                "connectionPolicy"
        );
    }

    public static WorkerRetryPolicy defaults() {
        return DEFAULT;
    }

    public static WorkerRetryPolicy of(
            int maxPrepareAttempts,
            Duration prepareRetryInterval,
            TextMessageReconnectPolicy connectionPolicy
    ) {
        return new WorkerRetryPolicy(
                maxPrepareAttempts,
                prepareRetryInterval,
                connectionPolicy
        );
    }

    public int maxPrepareAttempts() {
        return maxPrepareAttempts;
    }

    public Duration prepareRetryInterval() {
        return prepareRetryInterval;
    }

    public TextMessageReconnectPolicy connectionPolicy() {
        return connectionPolicy;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
