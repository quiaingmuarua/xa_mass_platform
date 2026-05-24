package com.xa.mass.engine.worker;

public record WorkerCandidateSamplingContext(
        String groupId,
        String adapterNodeId,
        String routeBucketKey
) {
}
