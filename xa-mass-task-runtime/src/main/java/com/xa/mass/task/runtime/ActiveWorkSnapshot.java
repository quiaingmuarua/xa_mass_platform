package com.xa.mass.task.runtime;

import java.util.List;

public record ActiveWorkSnapshot(String workerId, List<ActiveLeaseRepairCandidate> activeItems) {

    public ActiveWorkSnapshot {
        workerId = TaskRuntimeContractChecks.requireText(workerId, "workerId");
        activeItems = TaskRuntimeContractChecks.copyList(activeItems);
    }

    public boolean hasActiveWork() {
        return !activeItems.isEmpty();
    }
}
