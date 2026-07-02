package com.xa.mass.task.runtime;

public interface TaskRuntimeConvergencePort {

    RetryPromotionBatch promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit);

    /**
     * Finds expired active leases without mutating runtime state.
     * The caller must apply timeout finality through {@link #applyResult(RuntimeResultFact)}.
     */
    LeaseRepairBatch scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit);

    MessageFinalityOutcome applyResult(RuntimeResultFact fact);

    TaskCloseAttemptOutcome closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch);

    DiscardTaskRuntimeOutcome discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason);

    DiscardTaskWorkOutcome discardWork(String taskId, RuntimeEpoch epoch, String reason);
}
