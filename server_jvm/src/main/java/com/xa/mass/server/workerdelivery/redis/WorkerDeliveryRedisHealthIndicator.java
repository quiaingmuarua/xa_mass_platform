package com.xa.mass.server.workerdelivery.redis;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("workerDeliveryRedis")
final class WorkerDeliveryRedisHealthIndicator implements HealthIndicator {

    private final RedisWorkerDeliveryRuntime runtime;

    WorkerDeliveryRedisHealthIndicator(RedisWorkerDeliveryRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        try {
            return runtime.ping()
                    ? Health.up().build()
                    : Health.down().build();
        } catch (RuntimeException error) {
            return Health.down(error).build();
        }
    }
}
