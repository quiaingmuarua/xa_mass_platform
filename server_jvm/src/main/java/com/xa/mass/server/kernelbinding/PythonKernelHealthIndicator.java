package com.xa.mass.server.kernelbinding;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kernel")
final class PythonKernelHealthIndicator implements HealthIndicator {

    private final PythonKernelHttpTransport transport;

    PythonKernelHealthIndicator(PythonKernelHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public Health health() {
        return transport.isHealthy()
                ? Health.up().build()
                : Health.down().build();
    }
}
