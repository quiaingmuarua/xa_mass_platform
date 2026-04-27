package com.xa.mass.engine.work;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskWorkRuntime {

    WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options);

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
