package com.xa.mass.task.runtime;

public record UpdateSchedulerEligibilityCommand(
        String taskId,
        SchedulerEligibilityPolicy eligibilityPolicy,
        RuntimeEpoch runtimeEpoch
) {

    public UpdateSchedulerEligibilityCommand {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        if (eligibilityPolicy == null) {
            throw new IllegalArgumentException("eligibilityPolicy is required");
        }
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
    }
}
