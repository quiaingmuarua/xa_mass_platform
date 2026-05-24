package com.xa.mass.engine.worker;

/**
 * Controls bounded cleanup pacing for stale worker registry indexes.
 */
public interface WorkerCleanupPolicy {

    int cleanupLimit(String groupId, String reason);
}
