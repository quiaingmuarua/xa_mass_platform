package com.xa.mass.task.runtime;

public record DiscardTaskWorkOutcome(
        String taskId,
        long discardedReadyItems,
        long discardedActiveItems
) {

    public DiscardTaskWorkOutcome {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        discardedReadyItems = Math.max(0L, discardedReadyItems);
        discardedActiveItems = Math.max(0L, discardedActiveItems);
    }
}
