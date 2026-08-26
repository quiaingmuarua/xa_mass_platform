package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
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
                && snapshot.runtime().state()
                == KernelPacerRuntime.State.RUNNING
                ? Health.up()
                : Health.down();
        health.withDetail(
                "mode",
                "java-kernel-pacers"
        );
        health.withDetail("state", snapshot.runtime().state().name());
        health.withDetail(
                "javaResultRoutingState",
                snapshot.runtime().resultRoutingState()
        );
        health.withDetail(
                "javaWorkerServiceabilityResultState",
                snapshot.runtime().workerServiceabilityResultState()
        );
        health.withDetail(
                "javaWorkerServiceabilityDispatchState",
                snapshot.runtime().workerServiceabilityDispatchState()
        );
        health.withDetail(
                "javaAssignmentDispatchState",
                snapshot.runtime().assignmentDispatchState()
        );
        return health.build();
    }
}
