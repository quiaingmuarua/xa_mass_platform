package com.xa.mass.engine.worker;

/**
 * Context for bounded Stage-1 route-bucket candidate acquisition.
 */
public record WorkerRouteBucketSelectionContext(
        String groupId,
        String adapterNodeId,
        String routeBucketKey
) {
}
