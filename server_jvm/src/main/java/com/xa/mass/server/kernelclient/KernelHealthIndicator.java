package com.xa.mass.server.kernelclient;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kernel")
final class KernelHealthIndicator implements HealthIndicator {

    private final KernelCommandClient kernelClient;

    KernelHealthIndicator(KernelCommandClient kernelClient) {
        this.kernelClient = kernelClient;
    }

    @Override
    public Health health() {
        return kernelClient.isHealthy()
                ? Health.up().build()
                : Health.down().build();
    }
}
