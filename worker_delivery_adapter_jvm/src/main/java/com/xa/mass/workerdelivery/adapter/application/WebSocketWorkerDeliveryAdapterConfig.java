package com.xa.mass.workerdelivery.adapter.application;

import java.time.Duration;

public record WebSocketWorkerDeliveryAdapterConfig(
        Duration sendTimeLimit
) implements WorkerDeliveryAdapterPrivateConfig {

    public WebSocketWorkerDeliveryAdapterConfig {
        if (sendTimeLimit == null
                || sendTimeLimit.isZero()
                || sendTimeLimit.isNegative()
                || sendTimeLimit.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "sendTimeLimit must be a positive int millis"
            );
        }
    }
}
