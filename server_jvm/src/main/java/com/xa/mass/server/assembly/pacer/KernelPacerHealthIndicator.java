package com.xa.mass.server.assembly.pacer;

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
                "javaResultConvergenceState",
                snapshot.runtime().resultConvergenceState()
        );
        health.withDetail(
                "javaDispatchConvergenceState",
                snapshot.runtime().dispatchConvergenceState()
        );
        return health.build();
    }
}
