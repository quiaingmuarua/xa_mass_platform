package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicies;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicy;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Engine-owned route bucket policy for Stage-1 worker candidate acquisition.
 *
 * <p>The default implementation only reads explicitly approved task route
 * attributes and worker attributes. It does not read arbitrary attributes and
 * does not change WorkerGroup capability truth.</p>
 */
public interface WorkerRoutingPolicy extends WorkerRouteBucketPolicy {

    String DEFAULT_ROUTE_BUCKET_KEY = WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY;
    List<String> STANDARD_APPROVED_ROUTE_ATTRIBUTES =
            WorkerRouteBucketPolicies.STANDARD_APPROVED_ROUTE_ATTRIBUTES;

    Set<String> routeBucketKeysForTask(Task task);

    Set<String> routeBucketKeysForWorker(Worker worker);

    static WorkerRoutingPolicy defaultPolicy() {
        return ApprovedAttributeRoutingPolicy.DEFAULT;
    }

    static WorkerRoutingPolicy approvedAttributePolicy(Collection<String> approvedAttributeKeys) {
        return new ApprovedAttributeRoutingPolicy(approvedAttributeKeys);
    }

    final class ApprovedAttributeRoutingPolicy implements WorkerRoutingPolicy {
        private static final ApprovedAttributeRoutingPolicy DEFAULT =
                new ApprovedAttributeRoutingPolicy(STANDARD_APPROVED_ROUTE_ATTRIBUTES);

        private final WorkerRouteBucketPolicies.ApprovedAttributeRouteBucketPolicy workerRoutePolicy;

        private ApprovedAttributeRoutingPolicy(Collection<String> approvedAttributeKeys) {
            this.workerRoutePolicy = WorkerRouteBucketPolicies.approvedAttributePolicy(approvedAttributeKeys);
        }

        @Override
        public Set<String> routeBucketKeysForTask(Task task) {
            return Set.of(workerRoutePolicy.exactRouteBucketKeyForAttributes(TaskSharedConfig.routeAttributes(task)));
        }

        @Override
        public Set<String> routeBucketKeysForWorker(Worker worker) {
            return workerRoutePolicy.routeBucketKeysForAttributes(worker == null ? null : worker.getAttributes());
        }

        @Override
        public Set<String> routeBucketKeysForWorkerMeta(WorkerMeta meta) {
            return workerRoutePolicy.routeBucketKeysForWorkerMeta(meta);
        }
    }
}
