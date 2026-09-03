package com.xa.mass.server.assembly.matching;

import com.xa.mass.workermatching.WorkerMatchingRuntime;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class WorkerMatchingHealthIndicator implements HealthIndicator {

    private final WorkerMatchingAssembly assembly;

    WorkerMatchingHealthIndicator(WorkerMatchingAssembly assembly) {
        this.assembly = assembly;
    }

    @Override
    public Health health() {
        WorkerMatchingRuntime.Snapshot snapshot = assembly.snapshot();
        Health.Builder health = snapshot.state()
                == WorkerMatchingRuntime.State.RUNNING
                ? Health.up()
                : Health.down();
        return health
                .withDetail("state", snapshot.state().name())
                .withDetail("queuedDemands", snapshot.queuedDemands())
                .withDetail("pendingDemands", snapshot.pendingDemands())
                .build();
    }
}
