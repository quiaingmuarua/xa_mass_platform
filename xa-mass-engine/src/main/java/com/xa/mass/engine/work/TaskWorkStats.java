package com.xa.mass.engine.work;

public record TaskWorkStats(long totalCount,
                            long readyCount,
                            long inflightCount,
                            long delayedCount,
                            long successCount,
                            long failedCount,
                            long expiredCount) {
    public static final TaskWorkStats EMPTY = new TaskWorkStats(0, 0, 0, 0, 0, 0, 0);

    public long finalCount() {
        return successCount + failedCount + expiredCount;
    }

    public long pendingCount() {
        return Math.max(totalCount - finalCount(), 0);
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
