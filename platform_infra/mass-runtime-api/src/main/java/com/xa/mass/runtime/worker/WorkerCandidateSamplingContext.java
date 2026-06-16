package com.xa.mass.runtime.worker;

public record WorkerCandidateSamplingContext(
        String groupId,
        String candidateBucketKey
) {
}
