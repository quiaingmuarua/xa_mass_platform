package com.xa.mass.task.runtime;

public record TaskRuntimeProgressSnapshot(
        String taskId,
        long totalCount,
        long readyCount,
        long delayedCount,
        long activeCount,
        long successCount,
        long failedCount,
        long expiredCount
) {

    public TaskRuntimeProgressSnapshot {
        taskId = TaskRuntimeContractChecks.requireText(taskId, "taskId");
        readyCount = Math.max(0L, readyCount);
        delayedCount = Math.max(0L, delayedCount);
        activeCount = Math.max(0L, activeCount);
        successCount = Math.max(0L, successCount);
        failedCount = Math.max(0L, failedCount);
        expiredCount = Math.max(0L, expiredCount);
        totalCount = Math.max(totalCount, readyCount + delayedCount + activeCount
                + successCount + failedCount + expiredCount);
    }

    public static TaskRuntimeProgressSnapshot empty(String taskId) {
        return new TaskRuntimeProgressSnapshot(taskId, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public long finalCount() {
        return successCount + failedCount + expiredCount;
    }

    public long processingCount() {
        return readyCount + delayedCount + activeCount;
    }
}
