package com.xa.mass.server.workerdelivery.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("xa.mass.worker-delivery.websocket")
public record WorkerWebSocketProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String endpointManagerId,
        @DefaultValue("100ms") Duration pumpInterval,
        @DefaultValue("100") int scanCount,
        @DefaultValue("100") int resultBatchSize,
        @DefaultValue("1000") int resultBufferCapacity,
        @DefaultValue("5s") Duration sendTimeLimit
) {
    public WorkerWebSocketProperties {
        if (pumpInterval == null
                || pumpInterval.isZero()
                || pumpInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "WebSocket pumpInterval must be positive"
            );
        }
        if (scanCount <= 0
                || resultBatchSize <= 0
                || resultBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "WebSocket bounds must be positive"
            );
        }
        if (sendTimeLimit == null
                || sendTimeLimit.isZero()
                || sendTimeLimit.isNegative()
                || sendTimeLimit.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "WebSocket sendTimeLimit must be a positive int millis"
            );
        }
        if (enabled && (endpointManagerId == null
                || endpointManagerId.isBlank())) {
            throw new IllegalArgumentException(
                    "WebSocket endpointManagerId must be non-blank"
            );
        }
        if (enabled && SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                endpointManagerId
        )) {
            throw new IllegalArgumentException(
                    "system-polling cannot own a WebSocket Adapter"
            );
        }
    }
}
