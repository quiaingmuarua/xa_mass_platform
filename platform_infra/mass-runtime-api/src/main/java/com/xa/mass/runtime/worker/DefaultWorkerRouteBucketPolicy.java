package com.xa.mass.runtime.worker;

import java.util.Set;

/**
 * Registry-neutral route policy used when no platform route policy is injected.
 */
public final class DefaultWorkerRouteBucketPolicy {

    private static final WorkerRouteBucketPolicy DEFAULT =
            meta -> Set.of(WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY);

    private DefaultWorkerRouteBucketPolicy() {
    }

    public static WorkerRouteBucketPolicy defaultPolicy() {
        return DEFAULT;
    }
}
