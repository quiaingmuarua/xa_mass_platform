package com.xa.mass.task.runtime;

public record SchedulerTaskCandidate(String taskId, RuntimeEpoch runtimeEpoch, long eligibleAtMillis) {

    public SchedulerTaskCandidate {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
        eligibleAtMillis = Math.max(0L, eligibleAtMillis);
    }
}
