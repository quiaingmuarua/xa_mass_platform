package com.xa.mass.base.runtime;

/**
 * Snapshot of a runtime executor's admission and execution state.
 */
public final class RuntimeTaskExecutorStatistics {
    private final long submittedTasks;
    private final long completedTasks;
    private final long rejectedTasks;
    private final int activeTasks;
    private final int pendingTasks;
    private final int maxPendingTasks;

    public RuntimeTaskExecutorStatistics(long submittedTasks,
                                         long completedTasks,
                                         long rejectedTasks,
                                         int activeTasks,
                                         int pendingTasks,
                                         int maxPendingTasks) {
        this.submittedTasks = submittedTasks;
        this.completedTasks = completedTasks;
        this.rejectedTasks = rejectedTasks;
        this.activeTasks = activeTasks;
        this.pendingTasks = pendingTasks;
        this.maxPendingTasks = maxPendingTasks;
    }

    public long getSubmittedTasks() {
        return submittedTasks;
    }

    public long getCompletedTasks() {
        return completedTasks;
    }

    public long getRejectedTasks() {
        return rejectedTasks;
    }

    public int getActiveTasks() {
        return activeTasks;
    }

    public int getPendingTasks() {
        return pendingTasks;
    }

    public int getMaxPendingTasks() {
        return maxPendingTasks;
    }
}
