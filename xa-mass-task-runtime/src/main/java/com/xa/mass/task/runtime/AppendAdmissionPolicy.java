package com.xa.mass.task.runtime;

/**
 * Caller-resolved append admission limits passed to task-runtime.
 */
public record AppendAdmissionPolicy(int maxAppendBatchSize, long maxReadyBacklogItems) {

    public static final long UNLIMITED_READY_BACKLOG = -1L;

    public AppendAdmissionPolicy {
        if (maxAppendBatchSize <= 0) {
            throw new IllegalArgumentException("maxAppendBatchSize must be positive");
        }
        maxReadyBacklogItems = maxReadyBacklogItems <= 0 ? UNLIMITED_READY_BACKLOG : maxReadyBacklogItems;
    }
}
