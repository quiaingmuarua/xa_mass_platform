package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRouteBucketPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    List<String> STANDARD_APPROVED_ROUTE_ATTRIBUTES = List.of("business", "tenant", "region", "pool");

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

        private final List<String> approvedAttributeKeys;

        private ApprovedAttributeRoutingPolicy(Collection<String> approvedAttributeKeys) {
            this.approvedAttributeKeys = normalizeApprovedKeys(approvedAttributeKeys);
        }

        @Override
        public Set<String> routeBucketKeysForTask(Task task) {
            Map<String, String> routeAttributes = approvedAttributes(TaskSharedConfig.routeAttributes(task));
            if (routeAttributes.isEmpty()) {
                return Set.of(DEFAULT_ROUTE_BUCKET_KEY);
            }
            return Set.of(bucketKey(routeAttributes));
        }

        @Override
        public Set<String> routeBucketKeysForWorker(Worker worker) {
            Map<String, String> workerAttributes = approvedAttributes(worker == null ? null : worker.getAttributes());
            return routeBucketKeysForWorkerAttributes(workerAttributes);
        }

        @Override
        public Set<String> routeBucketKeysForWorkerMeta(WorkerMeta meta) {
            Map<String, String> workerAttributes = approvedAttributes(meta == null ? null : meta.attributes());
            return routeBucketKeysForWorkerAttributes(workerAttributes);
        }

        private Set<String> routeBucketKeysForWorkerAttributes(Map<String, String> workerAttributes) {
            if (workerAttributes.isEmpty()) {
                return Set.of(DEFAULT_ROUTE_BUCKET_KEY);
            }
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            keys.add(DEFAULT_ROUTE_BUCKET_KEY);
            for (Map<String, String> combination : combinations(workerAttributes)) {
                keys.add(bucketKey(combination));
            }
            return Set.copyOf(keys);
        }

        private Map<String, String> approvedAttributes(Map<String, String> attributes) {
            if (attributes == null || attributes.isEmpty() || approvedAttributeKeys.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
            for (String key : approvedAttributeKeys) {
                String value = attributes.get(key);
                if (value != null && !value.isBlank()) {
                    normalized.put(key, value.trim());
                }
            }
            return normalized;
        }

        private String bucketKey(Map<String, String> attributes) {
            StringBuilder builder = new StringBuilder("attr:");
            boolean first = true;
            for (String key : approvedAttributeKeys) {
                String value = attributes.get(key);
                if (value == null) {
                    continue;
                }
                if (!first) {
                    builder.append('|');
                }
                builder.append(escape(key)).append('=').append(escape(value));
                first = false;
            }
            return first ? DEFAULT_ROUTE_BUCKET_KEY : builder.toString();
        }

        private List<Map<String, String>> combinations(Map<String, String> attributes) {
            List<Map<String, String>> combinations = new ArrayList<>();
            List<String> presentKeys = approvedAttributeKeys.stream()
                    .filter(attributes::containsKey)
                    .toList();
            int count = presentKeys.size();
            for (int mask = 1; mask < (1 << count); mask++) {
                LinkedHashMap<String, String> combination = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    if ((mask & (1 << i)) != 0) {
                        String key = presentKeys.get(i);
                        combination.put(key, attributes.get(key));
                    }
                }
                combinations.add(combination);
            }
            return combinations;
        }

        private static List<String> normalizeApprovedKeys(Collection<String> approvedAttributeKeys) {
            if (approvedAttributeKeys == null || approvedAttributeKeys.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String key : approvedAttributeKeys) {
                if (key != null && !key.isBlank()) {
                    normalized.add(key.trim());
                }
            }
            return List.copyOf(normalized);
        }

        private static String escape(String value) {
            return value
                    .replace("%", "%25")
                    .replace("|", "%7C")
                    .replace("=", "%3D");
        }
    }
}
