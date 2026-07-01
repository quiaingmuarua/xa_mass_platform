package com.xa.mass.sdk.model;

/**
 * SDK-owned bounded snapshot of runtime work counters for diagnostics.
 */
public record TaskWorkStatsSnapshot(long totalCount,
                                    long readyCount,
                                    long inflightCount,
                                    long delayedCount,
                                    long successCount,
                                    long failedCount,
                                    long expiredCount,
                                    long finalCount) {

    public static final TaskWorkStatsSnapshot EMPTY = new TaskWorkStatsSnapshot(0, 0, 0, 0, 0, 0, 0, 0);

    public TaskWorkStatsSnapshot {
        totalCount = Math.max(0L, totalCount);
        readyCount = Math.max(0L, readyCount);
        inflightCount = Math.max(0L, inflightCount);
        delayedCount = Math.max(0L, delayedCount);
        successCount = Math.max(0L, successCount);
        failedCount = Math.max(0L, failedCount);
        expiredCount = Math.max(0L, expiredCount);
        finalCount = Math.max(0L, finalCount);
    }

    public long pendingCount() {
        return Math.max(totalCount - finalCount, 0L);
    }

    public long processingCount() {
        return readyCount + inflightCount + delayedCount;
    }

    public double successRate() {
        return totalCount == 0 ? 0.0 : (double) successCount / totalCount * 100.0;
    }

    public double failureRate() {
        return totalCount == 0 ? 0.0 : (double) (failedCount + expiredCount) / totalCount * 100.0;
    }
}
