package com.xa.mass.server.kernelredis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class KernelRedisHealthIndicator implements HealthIndicator {

    private final RedisClient redisClient;

    public KernelRedisHealthIndicator(RedisClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public Health health() {
        try (var connection = redisClient.connect(StringCodec.UTF8)) {
            return "PONG".equals(connection.sync().ping())
                    ? Health.up().build()
                    : Health.down().build();
        } catch (RuntimeException error) {
            return Health.down(error).build();
        }
    }
}
