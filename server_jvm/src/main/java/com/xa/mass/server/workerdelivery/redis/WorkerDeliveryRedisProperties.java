package com.xa.mass.server.workerdelivery.redis;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xa.mass.worker-delivery")
public record WorkerDeliveryRedisProperties(
        URI redisUrl,
        String redisPrefix
) {
    public WorkerDeliveryRedisProperties {
        Objects.requireNonNull(redisUrl, "redisUrl");
        if (!"redis".equalsIgnoreCase(redisUrl.getScheme())
                && !"rediss".equalsIgnoreCase(redisUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "redisUrl must use redis or rediss"
            );
        }
        if (redisPrefix == null || redisPrefix.isBlank()) {
            throw new IllegalArgumentException("redisPrefix must be non-blank");
        }
    }
}
