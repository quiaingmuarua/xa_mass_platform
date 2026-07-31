package com.xa.mass.scenarioworkers;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record ScenarioWorkerBundleConfig(
        String bundleId,
        String endpointManagerId,
        URI workerWebSocketUri,
        String workerGroupId,
        String workerIdPrefix,
        int workerCount,
        Duration requestTimeout,
        Duration reconnectInterval,
        Duration connectTimeout
) {

    public ScenarioWorkerBundleConfig {
        requireNonBlank(bundleId, "bundleId");
        requireNonBlank(endpointManagerId, "endpointManagerId");
        requireWebSocketUri(workerWebSocketUri);
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerIdPrefix, "workerIdPrefix");
        if (workerCount < 1 || workerCount > 100) {
            throw new IllegalArgumentException(
                    "workerCount must be between 1 and 100"
            );
        }
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(reconnectInterval, "reconnectInterval");
        requirePositive(connectTimeout, "connectTimeout");
    }

    private static void requireNonBlank(
            String value,
            String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }

    private static void requirePositive(
            Duration value,
            String name
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
    }

    private static void requireWebSocketUri(URI value) {
        Objects.requireNonNull(value, "workerWebSocketUri");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || (!"ws".equalsIgnoreCase(scheme)
                && !"wss".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "workerWebSocketUri must be an absolute ws/wss URI"
            );
        }
    }
}
