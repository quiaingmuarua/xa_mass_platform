package com.xa.mass.task.runtime;

/**
 * Task-level scheduling eligibility snapshot. It is not item backlog truth.
 */
public record SchedulerEligibilityPolicy(
        RuntimeGate runtimeGate,
        String dispatchLane,
        long nextEligibleAtMillis,
        long positiveMatchDelayMillis,
        long emptyMatchDelayMillis,
        long contentionRecheckDelayMillis
) {

    public SchedulerEligibilityPolicy {
        runtimeGate = runtimeGate == null ? RuntimeGate.OPEN : runtimeGate;
        dispatchLane = TaskRuntimeContractChecks.requireText(dispatchLane, "dispatchLane");
        positiveMatchDelayMillis = Math.max(0L, positiveMatchDelayMillis);
        emptyMatchDelayMillis = Math.max(0L, emptyMatchDelayMillis);
        contentionRecheckDelayMillis = Math.max(0L, contentionRecheckDelayMillis);
    }
}
