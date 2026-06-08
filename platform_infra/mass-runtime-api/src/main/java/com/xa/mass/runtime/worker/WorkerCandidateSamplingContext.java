package com.xa.mass.runtime.worker;

public record WorkerCandidateSamplingContext(
        String groupId,
        String adapterNodeId,
        String candidateBucketKey
) {
}
