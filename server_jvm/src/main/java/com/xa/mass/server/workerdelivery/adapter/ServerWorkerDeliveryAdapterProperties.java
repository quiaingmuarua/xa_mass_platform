package com.xa.mass.server.workerdelivery.adapter;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("xa.mass.worker-delivery.adapter.websocket")
public record ServerWorkerDeliveryAdapterProperties(
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
    public ServerWorkerDeliveryAdapterProperties {
        if (gatewayBaseUrl == null
                || !gatewayBaseUrl.isAbsolute()
                || gatewayBaseUrl.getHost() == null
                || !isHttp(gatewayBaseUrl)) {
            throw new IllegalArgumentException(
                    "gatewayBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(pumpInterval, "pumpInterval");
        requirePositive(sendTimeLimit, "sendTimeLimit");
        if (sendTimeLimit.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "sendTimeLimit must fit int milliseconds"
            );
        }
        if (scanCount <= 0
                || resultBatchSize <= 0
                || resultBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
            );
        }
        if (enabled
                && (endpointManagerId == null
                || endpointManagerId.isBlank())) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
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

    private static boolean isHttp(URI value) {
        return "http".equalsIgnoreCase(value.getScheme())
                || "https".equalsIgnoreCase(value.getScheme());
    }

    private static void requirePositive(
            Duration value,
            String name
    ) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
    }
}
