package com.xa.mass.task.runtime;

public record TaskRuntimeMetaV1(
        String taskId,
        String laneKey,
        RuntimeGate runtimeGate,
        RuntimeEpoch runtimeEpoch,
        long nextEligibleAtMillis,
        long positiveMatchDelayMillis,
        long emptyMatchDelayMillis,
        long contentionRecheckDelayMillis,
        TaskRuntimeResultPolicyV1 resultPolicy
) {

    public TaskRuntimeMetaV1(String taskId,
                             String laneKey,
                             RuntimeGate runtimeGate,
                             RuntimeEpoch runtimeEpoch,
                             long nextEligibleAtMillis,
                             long positiveMatchDelayMillis,
                             long emptyMatchDelayMillis,
                             long contentionRecheckDelayMillis) {
        this(taskId,
                laneKey,
                runtimeGate,
                runtimeEpoch,
                nextEligibleAtMillis,
                positiveMatchDelayMillis,
                emptyMatchDelayMillis,
                contentionRecheckDelayMillis,
                TaskRuntimeResultPolicyV1.defaultPolicy());
    }

    public TaskRuntimeMetaV1 {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        laneKey = TaskRuntimeContractChecks.requireText(laneKey, "laneKey");
        runtimeGate = runtimeGate == null ? RuntimeGate.OPEN : runtimeGate;
        runtimeEpoch = runtimeEpoch == null ? RuntimeEpoch.of(taskId, 0L) : runtimeEpoch;
        nextEligibleAtMillis = Math.max(0L, nextEligibleAtMillis);
        positiveMatchDelayMillis = Math.max(0L, positiveMatchDelayMillis);
        emptyMatchDelayMillis = Math.max(0L, emptyMatchDelayMillis);
        contentionRecheckDelayMillis = Math.max(0L, contentionRecheckDelayMillis);
        resultPolicy = resultPolicy == null ? TaskRuntimeResultPolicyV1.defaultPolicy() : resultPolicy;
    }

    public static TaskRuntimeMetaV1 fromPolicy(String taskId,
                                               SchedulerEligibilityPolicy policy,
                                               RuntimeEpoch runtimeEpoch) {
        if (policy == null) {
            throw new IllegalArgumentException("policy is required");
        }
        return new TaskRuntimeMetaV1(
                taskId,
                policy.dispatchLane(),
                policy.runtimeGate(),
                runtimeEpoch,
                policy.nextEligibleAtMillis(),
                policy.positiveMatchDelayMillis(),
                policy.emptyMatchDelayMillis(),
                policy.contentionRecheckDelayMillis(),
                TaskRuntimeResultPolicyV1.defaultPolicy());
    }

    public SchedulerEligibilityPolicy toEligibilityPolicy() {
        return new SchedulerEligibilityPolicy(
                runtimeGate,
                laneKey,
                nextEligibleAtMillis,
                positiveMatchDelayMillis,
                emptyMatchDelayMillis,
                contentionRecheckDelayMillis);
    }
}
