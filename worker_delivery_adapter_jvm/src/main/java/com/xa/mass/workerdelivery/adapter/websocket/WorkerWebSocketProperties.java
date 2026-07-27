package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("xa.mass.worker-delivery.adapter.websocket")
public record WorkerWebSocketProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String endpointManagerId,
        @DefaultValue("http://127.0.0.1:18082") URI gatewayBaseUrl,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("100ms") Duration pumpInterval,
        @DefaultValue("100") int scanCount,
        @DefaultValue("100") int resultBatchSize,
        @DefaultValue("1000") int resultBufferCapacity,
        @DefaultValue("5s") Duration sendTimeLimit
) {
    public WorkerWebSocketProperties {
        if (gatewayBaseUrl == null
                || !gatewayBaseUrl.isAbsolute()
                || !isHttp(gatewayBaseUrl)
                || gatewayBaseUrl.getHost() == null) {
            throw new IllegalArgumentException(
                    "WebSocket gatewayBaseUrl must be an absolute HTTP URL"
            );
        }
        if (requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "WebSocket requestTimeout must be positive"
            );
        }
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

    private static boolean isHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
    }
}
