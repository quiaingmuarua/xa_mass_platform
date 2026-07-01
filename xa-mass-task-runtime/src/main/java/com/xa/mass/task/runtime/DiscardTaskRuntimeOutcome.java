package com.xa.mass.task.runtime;

public record DiscardTaskRuntimeOutcome(
        String taskId,
        long discardedReadyItems,
        long discardedActiveItems,
        long discardedFinalResults
) {

    public DiscardTaskRuntimeOutcome {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        discardedReadyItems = Math.max(0L, discardedReadyItems);
        discardedActiveItems = Math.max(0L, discardedActiveItems);
        discardedFinalResults = Math.max(0L, discardedFinalResults);
    }
}
