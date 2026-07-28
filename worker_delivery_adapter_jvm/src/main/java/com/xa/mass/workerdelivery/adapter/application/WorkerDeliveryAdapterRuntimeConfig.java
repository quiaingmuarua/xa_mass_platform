package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.net.URI;
import java.time.Duration;

public record WorkerDeliveryAdapterRuntimeConfig(
        String endpointManagerId,
        URI gatewayBaseUrl,
        Duration requestTimeout,
        Duration dispatchInterval,
        int scanCount,
        int resultBatchSize,
        int resultBufferCapacity
) {

    public WorkerDeliveryAdapterRuntimeConfig {
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(endpointManagerId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own a Worker Adapter"
            );
        }
        if (gatewayBaseUrl == null
                || !gatewayBaseUrl.isAbsolute()
                || gatewayBaseUrl.getHost() == null
                || !isHttp(gatewayBaseUrl)) {
            throw new IllegalArgumentException(
                    "gatewayBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(dispatchInterval, "dispatchInterval");
        if (scanCount <= 0
                || resultBatchSize <= 0
                || resultBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Adapter bounds must be positive"
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
