package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;

import java.util.Set;

/**
 * Engine-owned route bucket policy for Stage-1 worker candidate acquisition.
 *
 * <p>The first slice deliberately approves no worker attributes for routing,
 * so every non-targeted task and worker maps to the default bucket. Later
 * slices can replace this policy without changing WorkerGroup capability
 * truth.</p>
 */
public interface WorkerRoutingPolicy {

    String DEFAULT_ROUTE_BUCKET_KEY = "default";

    Set<String> routeBucketKeysForTask(Task task);

    Set<String> routeBucketKeysForWorker(Worker worker);

    static WorkerRoutingPolicy defaultPolicy() {
        return DefaultWorkerRoutingPolicy.INSTANCE;
    }

    final class DefaultWorkerRoutingPolicy implements WorkerRoutingPolicy {
        private static final DefaultWorkerRoutingPolicy INSTANCE = new DefaultWorkerRoutingPolicy();

        private DefaultWorkerRoutingPolicy() {
        }

        @Override
        public Set<String> routeBucketKeysForTask(Task task) {
            return Set.of(DEFAULT_ROUTE_BUCKET_KEY);
        }

        @Override
        public Set<String> routeBucketKeysForWorker(Worker worker) {
            return Set.of(DEFAULT_ROUTE_BUCKET_KEY);
        }
    }
}
