package com.xa.mass.server.kernelpacer;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class KernelPacerHealthIndicator implements HealthIndicator {

    private final KernelPacerAssembly assembly;

    KernelPacerHealthIndicator(KernelPacerAssembly assembly) {
        this.assembly = assembly;
    }

    @Override
    public Health health() {
        KernelPacerAssembly.Snapshot snapshot = assembly.snapshot();
        Health.Builder health = snapshot.enabled()
                && snapshot.state() == KernelPacerAssembly.State.RUNNING
                ? Health.up()
                : Health.down();
        health.withDetail(
                "mode",
                "java-kernel-pacers"
        );
        health.withDetail("state", snapshot.state().name());
        health.withDetail(
                "javaResultRoutingState",
                snapshot.resultRoutingState()
        );
        health.withDetail(
                "javaWorkerServiceabilityResultState",
                snapshot.workerServiceabilityResultState()
        );
        health.withDetail(
                "javaWorkerServiceabilityDispatchState",
                snapshot.workerServiceabilityDispatchState()
        );
        health.withDetail(
                "javaAssignmentDispatchState",
                snapshot.assignmentDispatchState()
        );
        return health.build();
    }
}
