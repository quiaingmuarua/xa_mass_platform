package com.xa.mass.runtime.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskWorkRuntime {

    /**
     * Enqueues one logical work item into runtime-owned ready or delayed state.
     *
     * <p>Implementations must treat {@code taskId + messageId} as an idempotent
     * enqueue key rather than blindly appending duplicates.</p>
     */
    WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options);

    /**
     * Returns task ids that currently have runtime-ready work.
     *
     * <p>This is the runtime-owned dispatch recovery surface. Callers should
     * use it when they need to recover assignment signals from queue truth
     * instead of inferring readiness from task status alone.</p>
     */
    List<String> readyTaskIds(int limit);

    /**
     * Claims ready work for one task across the provided worker targets.
     *
     * <p>This is an exclusive runtime mutation. Claimed items must not remain
     * visible to another claimer until they converge through
     * {@link #applyResult(TaskWorkResult)} or lease-expiry handling.</p>
     */
    List<ClaimedTaskWork> claimReady(String taskId,
                                     List<WorkerClaimTarget> workers,
                                     TaskWorkClaimOptions options);

    default List<ClaimedTaskWork> claimReady(String taskId,
                                             List<WorkerClaimTarget> workers,
                                             int maxItems,
                                             long leaseSeconds) {
        return claimReady(taskId, workers, new TaskWorkClaimOptions(1, maxItems, leaseSeconds));
    }

    /**
     * Applies the only runtime-owned work completion mutation path.
     *
     * <p>Success, failure, retry scheduling, and expiry all converge through
     * this method instead of direct queue or lease manipulation by callers.</p>
     */
    ResultApplyOutcome applyResult(TaskWorkResult result);

    /**
     * Returns expired lease records using the provided cutoff time.
     *
     * <p>This reports runtime expiry truth but does not itself finalize the
     * owning logical message; engine-side result/expiry handling performs that
     * convergence.</p>
     */
    List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now);

    List<ActiveLeaseRecord> activeLeases(String taskId);

    Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId);

    /**
     * Returns the runtime-owned work envelope when the message is still under
     * queue/lease ownership.
     *
     * <p>This is a bounded runtime recovery read used to reconstruct hot-path
     * message metadata without falling back to compatibility projection
     * storage.</p>
     */
    default Optional<TaskWorkEnvelope> getWork(String taskId, String messageId) {
        return Optional.empty();
    }

    boolean hasReadyWork(String taskId);

    boolean hasActiveLeaseForWorker(String taskId, String workerId);

    TaskWorkStats stats(String taskId);

    TaskWorkRuntimeStats stats();

    /**
     * Discards all runtime-owned queue, delayed, and lease residue for one
     * task without affecting other tasks.
     */
    long discardTask(String taskId);

    void shutdown();
}

