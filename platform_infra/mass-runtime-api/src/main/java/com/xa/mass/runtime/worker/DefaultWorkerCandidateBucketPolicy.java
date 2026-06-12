package com.xa.mass.runtime.worker;

import java.util.Set;

/**
 * Registry-neutral candidate-bucket policy used when no platform bucket policy is injected.
 */
public final class DefaultWorkerCandidateBucketPolicy {

    private static final WorkerCandidateBucketPolicy DEFAULT = new WorkerCandidateBucketPolicy() {
        @Override
        public Set<String> candidateBucketKeysForWorkerMeta(WorkerMeta meta) {
            return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY);
        }

        @Override
        public int maxBucketFanout() {
            return 1;
        }
    };

    private DefaultWorkerCandidateBucketPolicy() {
    }

    public static WorkerCandidateBucketPolicy defaultPolicy() {
        return DEFAULT;
    }
}
