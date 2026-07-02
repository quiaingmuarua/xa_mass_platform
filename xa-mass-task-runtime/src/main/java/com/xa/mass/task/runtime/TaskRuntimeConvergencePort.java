package com.xa.mass.task.runtime;

import java.util.List;

public interface TaskRuntimeConvergencePort {

    List<String> promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit);

    /**
     * Finds expired active leases without mutating runtime state.
     * The caller must apply timeout finality through {@link #applyResult(RuntimeResultFact)}.
     */
    List<ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit);

    MessageFinalityOutcome applyResult(RuntimeResultFact fact);

    boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch);

    void discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason);

    void discardWork(String taskId, RuntimeEpoch epoch, String reason);
}
