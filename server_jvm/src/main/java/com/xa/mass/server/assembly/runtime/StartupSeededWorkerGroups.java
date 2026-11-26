package com.xa.mass.server.assembly.runtime;

import java.util.Set;

/** Read-only set of WorkerGroup IDs seeded from startup worker-assembly.group-config-json. */
public record StartupSeededWorkerGroups(Set<String> workerGroupIds) {
    public StartupSeededWorkerGroups {
        workerGroupIds = workerGroupIds == null ? Set.of() : Set.copyOf(workerGroupIds);
    }

    public static StartupSeededWorkerGroups empty() {
        return new StartupSeededWorkerGroups(Set.of());
    }

    public boolean contains(String workerGroupId) {
        return workerGroupId != null && workerGroupIds.contains(workerGroupId);
    }
}
