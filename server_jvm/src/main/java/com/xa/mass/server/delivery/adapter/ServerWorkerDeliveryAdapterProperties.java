package com.xa.mass.server.delivery.adapter;

import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerDeliveryAdapterConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        prefix = "xa.mass.worker-delivery.adapter",
        ignoreUnknownFields = false
)
public record ServerWorkerDeliveryAdapterProperties(
        @DefaultValue("http://127.0.0.1:18082") URI remoteBaseUrl,
        @DefaultValue("5s") Duration remoteRequestTimeout,
        @DefaultValue("100000") int verificationQueueCapacity,
        @DefaultValue("5s") Duration verificationTimeout,
        @DefaultValue Map<String, NettyWorkerDeliveryAdapterConfig> instances
) {

    public ServerWorkerDeliveryAdapterProperties {
        if (remoteBaseUrl == null
                || !remoteBaseUrl.isAbsolute()
                || remoteBaseUrl.getHost() == null
                || remoteBaseUrl.getRawQuery() != null
                || remoteBaseUrl.getRawFragment() != null
                || !isHttp(remoteBaseUrl)) {
            throw new IllegalArgumentException(
                    "remote-base-url must be an absolute HTTP(S) URI "
                            + "without query or fragment"
            );
        }
        if (remoteRequestTimeout == null
                || remoteRequestTimeout.isZero()
                || remoteRequestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "remote-request-timeout must be positive"
            );
        }
        if (verificationQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "verification-queue-capacity must be positive"
            );
        }
        if (verificationTimeout == null
                || verificationTimeout.isZero()
                || verificationTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "verification-timeout must be positive"
            );
        }
        if (instances == null) {
            instances = Map.of();
        } else {
            LinkedHashMap<String, NettyWorkerDeliveryAdapterConfig> copy =
                    new LinkedHashMap<>();
            instances.forEach((adapterId, config) -> {
                if (adapterId == null || adapterId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Adapter id must be non-blank"
                    );
                }
                if (config == null) {
                    throw new IllegalArgumentException(
                            "Adapter config must be present: " + adapterId
                    );
                }
                copy.put(adapterId, config);
            });
            instances = Collections.unmodifiableMap(copy);
        }
    }

    private static boolean isHttp(URI value) {
        return "http".equalsIgnoreCase(value.getScheme())
                || "https".equalsIgnoreCase(value.getScheme());
    }
}
