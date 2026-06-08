package com.xa.mass.runtime.worker;

import java.util.Set;

/**
 * Runtime candidate bucket policy used by task-side candidate requests,
 * worker-side membership indexing, and source-guard validation.
 */
public interface WorkerCandidateBucketPolicy {

    String DEFAULT_CANDIDATE_BUCKET_KEY = "default";

    Set<String> candidateBucketKeysForWorkerMeta(WorkerMeta meta);
}
