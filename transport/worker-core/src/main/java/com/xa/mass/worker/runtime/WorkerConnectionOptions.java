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
    private final WorkerGatewayCredential gatewayCredential;

    private WorkerConnectionOptions(
            Duration requestTimeout,
            TextMessageReconnectPolicy reconnectPolicy,
            WorkerGatewayCredential gatewayCredential
    ) {
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
        this.reconnectPolicy = Objects.requireNonNull(
                reconnectPolicy,
                "reconnectPolicy"
        );
        this.gatewayCredential = gatewayCredential;
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
                reconnectPolicy,
                null
        );
    }

    public WorkerConnectionOptions withGatewayCredential(WorkerGatewayCredential value) {
        return new WorkerConnectionOptions(requestTimeout, reconnectPolicy,
                Objects.requireNonNull(value, "gatewayCredential"));
    }

    /** Null means the existing unauthenticated transport behavior. */
    public WorkerGatewayCredential gatewayCredential() { return gatewayCredential; }

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
