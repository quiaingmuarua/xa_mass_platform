package com.xa.mass.task.runtime;

import java.util.List;

public record ActiveTaskWorkSnapshot(String taskId, List<ActiveLeaseRepairCandidate> activeItems) {

    public ActiveTaskWorkSnapshot {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        activeItems = TaskRuntimeContractChecks.copyList(activeItems);
    }
}
