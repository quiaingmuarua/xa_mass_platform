package com.xa.mass.task.runtime;

public record DiscardTaskRuntimeCommand(String taskId, RuntimeEpoch runtimeEpoch, String reason) {

    public DiscardTaskRuntimeCommand {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
        reason = reason == null ? "" : reason;
    }
}
