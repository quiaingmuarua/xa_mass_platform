package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import java.time.Duration;
import java.util.Objects;

/** Connection settings shared by Java and Android text-message Workers. */
public final class WorkerConnectionOptions {

    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(10);
    private static final WorkerConnectionOptions DEFAULT = of(
            DEFAULT_REQUEST_TIMEOUT,
            TextMessageReconnectPolicy.defaults()
    );

    private final Duration requestTimeout;
    private final TextMessageReconnectPolicy reconnectPolicy;

    private WorkerConnectionOptions(
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
    }

    public static WorkerConnectionOptions defaults() {
        return DEFAULT;
    }

    public static WorkerConnectionOptions of(
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy
    ) {
        return new WorkerConnectionOptions(
                requestTimeout,
                reconnectPolicy
        );
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public TextMessageReconnectPolicy reconnectPolicy() {
        return reconnectPolicy;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
