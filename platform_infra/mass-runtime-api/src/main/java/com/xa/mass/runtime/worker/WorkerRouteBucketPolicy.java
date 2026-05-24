package com.xa.mass.runtime.worker;

import java.util.Set;

/**
 * Runtime worker-side route bucket policy used when indexing worker slots.
 */
@FunctionalInterface
public interface WorkerRouteBucketPolicy {

    String DEFAULT_ROUTE_BUCKET_KEY = "default";

    Set<String> routeBucketKeysForWorkerMeta(WorkerMeta meta);
}
