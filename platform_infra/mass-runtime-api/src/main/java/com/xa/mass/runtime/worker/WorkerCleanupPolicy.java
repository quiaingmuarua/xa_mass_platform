package com.xa.mass.runtime.worker;

/**
 * Controls bounded cleanup pacing for stale worker registry indexes.
 */
public interface WorkerCleanupPolicy {

    int cleanupLimit(String groupId, String reason);
}
