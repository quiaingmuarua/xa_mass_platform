package com.xa.mass.runtime.api;

/**
 * Claim options for one task-level ready-work claim round.
 *
 * <p>This is the hot-path contract between task-level orchestration policy and
 * the runtime work queue/lease owner. The engine may resolve these options
 * from a workload-aware runtime profile before entering the claim path.
 */
public record TaskWorkClaimOptions(int perWorkerCapacity, int maxItems, long leaseSeconds) {

    public TaskWorkClaimOptions {
        perWorkerCapacity = Math.max(1, perWorkerCapacity);
        maxItems = Math.max(1, maxItems);
        leaseSeconds = Math.max(1L, leaseSeconds);
    }

    public static TaskWorkClaimOptions of(int perWorkerCapacity, int workerCount, long leaseSeconds) {
        int normalizedWorkerCount = Math.max(1, workerCount);
        int normalizedPerWorkerCapacity = Math.max(1, perWorkerCapacity);
        return new TaskWorkClaimOptions(
                normalizedPerWorkerCapacity,
                normalizedPerWorkerCapacity * normalizedWorkerCount,
                leaseSeconds
        );
    }
}

