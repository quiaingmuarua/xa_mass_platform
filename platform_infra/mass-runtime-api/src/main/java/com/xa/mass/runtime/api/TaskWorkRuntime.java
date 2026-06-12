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
     * Atomically applies a work result and returns the pre-apply lease and work
     * snapshot in one call, eliminating separate {@code getActiveLease} and
     * {@code getWork} round-trips on the hot callback path.
     *
     * <p>The default implementation is a non-atomic fallback that reads lease
     * and work before applying 鈥?it is safe for correctness but not for
     * million-scale throughput. {@code InMemoryTaskWorkRuntime} and
     * {@code RedisTaskWorkRuntime} both override this with a single atomic
     * operation (synchronized block / Lua script) so the three reads become
     * one.</p>
     *
     * <p>The returned context carries all fields needed by the engine callback
     * path without any additional runtime reads:</p>
     * <ul>
     *   <li>{@code workerId}, {@code batchId} 鈥?for
     *       routing and audit;</li>
     *   <li>{@code activeLeaseToken}, {@code retryCount} 鈥?for stale-lease
     *       detection and retry accounting;</li>
     *   <li>{@code payloadRef}, {@code maxRetryCount}, {@code leasedAt} 鈥?for
     *       projection and trace population.</li>
     * </ul>
     */
    default RuntimeResultApplyContext applyResultWithContext(TaskWorkResult result) {
        // Non-atomic fallback: read lease/work BEFORE apply so snapshot is available
        // even if apply deletes the lease. Implementations override with atomic version.
        Optional<ActiveLeaseRecord> leaseOpt = getActiveLease(result.taskId(), result.messageId());
        Optional<TaskWorkEnvelope> workOpt = getWork(result.taskId(), result.messageId());
        ResultApplyOutcome outcome = applyResult(result);
        if (leaseOpt.isEmpty()) {
            return RuntimeResultApplyContext.noLease(outcome);
        }
        ActiveLeaseRecord lease = leaseOpt.get();
        return RuntimeResultApplyContext.withSnapshot(
                outcome,
                lease.workerId(),
                lease.workerGroupId(),
                lease.batchId(),
                lease.leaseToken(),
                lease.payloadRef(),
                lease.retryCount(),
                workOpt.map(TaskWorkEnvelope::maxRetryCount).orElse(0),
                lease.leasedAt());
    }

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

    /**
     * Returns a bounded runtime-owned final receipt after a message has
     * already left queue and lease ownership.
     *
     * <p>This recovery read exists so duplicate/late callback handling can
     * remain runtime-first without forcing compatibility projection lookups on
     * the accepted hot path.</p>
     */
    default Optional<RecentFinalWorkReceipt> getRecentFinalReceipt(String taskId, String messageId) {
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
