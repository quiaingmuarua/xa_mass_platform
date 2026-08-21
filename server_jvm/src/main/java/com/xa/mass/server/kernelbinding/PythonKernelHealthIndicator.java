package com.xa.mass.server.kernelbinding;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kernel")
final class PythonKernelHealthIndicator implements HealthIndicator {

    private final PythonKernelHealthClient client;

    PythonKernelHealthIndicator(PythonKernelHealthClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        return client.isHealthy()
                ? Health.up().build()
                : Health.down().build();
    }
}
