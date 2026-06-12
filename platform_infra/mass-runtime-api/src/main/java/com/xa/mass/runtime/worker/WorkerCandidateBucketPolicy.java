package com.xa.mass.runtime.worker;

import java.util.Set;

/**
 * Runtime candidate bucket policy used by task-side candidate requests,
 * worker-side membership indexing, and source-guard validation.
 *
 * <p>The policy owns optional source-bucket indexing dimensions. Registry
 * implementations execute the policy but must not interpret worker attributes
 * directly. The declared fan-out keeps write amplification visible to callers
 * and reviewers.</p>
 */
public interface WorkerCandidateBucketPolicy {

    String DEFAULT_CANDIDATE_BUCKET_KEY = "default";

    Set<String> candidateBucketKeysForWorkerMeta(WorkerMeta meta);

    /**
     * Maximum number of source bucket memberships this policy can create for a
     * single worker. The value must include the default bucket when the policy
     * returns it.
     */
    int maxBucketFanout();
}
