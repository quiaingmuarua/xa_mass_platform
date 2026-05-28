package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicies;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicy;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Engine-owned route bucket policy for Stage-1 worker candidate acquisition.
 *
 * <p>The default implementation only reads explicitly approved task route
 * attributes. Worker-side bucket computation belongs to worker-runtime and
 * registry policy.</p>
 */
public interface WorkerRoutingPolicy {

    String DEFAULT_ROUTE_BUCKET_KEY = WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY;
    List<String> STANDARD_APPROVED_ROUTE_ATTRIBUTES =
            WorkerRouteBucketPolicies.STANDARD_APPROVED_ROUTE_ATTRIBUTES;

    Set<String> routeBucketKeysForTask(Task task);

    static WorkerRoutingPolicy defaultPolicy() {
        return ApprovedAttributeRoutingPolicy.DEFAULT;
    }

    static WorkerRoutingPolicy approvedAttributePolicy(Collection<String> approvedAttributeKeys) {
        return new ApprovedAttributeRoutingPolicy(approvedAttributeKeys);
    }

    final class ApprovedAttributeRoutingPolicy implements WorkerRoutingPolicy {
        private static final ApprovedAttributeRoutingPolicy DEFAULT =
                new ApprovedAttributeRoutingPolicy(STANDARD_APPROVED_ROUTE_ATTRIBUTES);

        private final WorkerRouteBucketPolicies.ApprovedAttributeRouteBucketPolicy routePolicy;

        private ApprovedAttributeRoutingPolicy(Collection<String> approvedAttributeKeys) {
            this.routePolicy = WorkerRouteBucketPolicies.approvedAttributePolicy(approvedAttributeKeys);
        }

        @Override
        public Set<String> routeBucketKeysForTask(Task task) {
            return Set.of(routePolicy.exactRouteBucketKeyForAttributes(TaskSharedConfig.routeAttributes(task)));
        }
    }
}
