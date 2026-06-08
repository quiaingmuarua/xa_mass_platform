package com.xa.mass.runtime.worker;

import java.util.Set;

/**
 * Registry-neutral candidate-bucket policy used when no platform bucket policy is injected.
 */
public final class DefaultWorkerCandidateBucketPolicy {

    private static final WorkerCandidateBucketPolicy DEFAULT =
            meta -> Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY);

    private DefaultWorkerCandidateBucketPolicy() {
    }

    public static WorkerCandidateBucketPolicy defaultPolicy() {
        return DEFAULT;
    }
}
