package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskWorkRuntime {

    WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options);

    /**
     * Returns task ids that currently have runtime-ready work.
     *
     * <p>This is the runtime-owned dispatch recovery surface. Callers should
     * use it when they need to recover assignment signals from queue truth
     * instead of inferring readiness from task status alone.</p>
     */
    List<String> readyTaskIds(int limit);

    List<ClaimedTaskWork> claimReady(String taskId,
                                     List<WorkerClaimTarget> workers,
                                     TaskWorkClaimOptions options);

    default List<ClaimedTaskWork> claimReady(String taskId,
                                             List<WorkerClaimTarget> workers,
                                             int maxItems,
                                             long leaseSeconds) {
        return claimReady(taskId, workers, new TaskWorkClaimOptions(1, maxItems, leaseSeconds));
    }

    ResultApplyOutcome applyResult(TaskWorkResult result);

    List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now);

    List<ActiveLeaseRecord> activeLeases(String taskId);

    Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId);

    boolean hasReadyWork(String taskId);

    boolean hasActiveLeaseForWorker(String taskId, String workerId);

    TaskWorkStats stats(String taskId);

    TaskWorkRuntimeStats stats();

    long discardTask(String taskId);

    void shutdown();
}

