package com.xa.mass.worker.runtime.routing;

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
 * Shared route-bucket policy helpers for worker runtime indexes.
 */
public final class WorkerRouteBucketPolicies {

    public static final String DEFAULT_ROUTE_BUCKET_KEY = WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY;

    public static final List<String> STANDARD_APPROVED_ROUTE_ATTRIBUTES =
            List.of("business", "tenant", "region", "pool");

    private static final ApprovedAttributeRouteBucketPolicy DEFAULT =
            new ApprovedAttributeRouteBucketPolicy(STANDARD_APPROVED_ROUTE_ATTRIBUTES);

    private WorkerRouteBucketPolicies() {
    }

    public static WorkerRouteBucketPolicy defaultPolicy() {
        return DEFAULT;
    }

    public static ApprovedAttributeRouteBucketPolicy approvedAttributePolicy(
            Collection<String> approvedAttributeKeys) {
        return new ApprovedAttributeRouteBucketPolicy(approvedAttributeKeys);
    }

    public static final class ApprovedAttributeRouteBucketPolicy implements WorkerRouteBucketPolicy {
        private final List<String> approvedAttributeKeys;

        private ApprovedAttributeRouteBucketPolicy(Collection<String> approvedAttributeKeys) {
            this.approvedAttributeKeys = normalizeApprovedKeys(approvedAttributeKeys);
        }

        @Override
        public Set<String> routeBucketKeysForWorkerMeta(WorkerMeta meta) {
            return routeBucketKeysForAttributes(meta == null ? null : meta.attributes());
        }

        public Set<String> routeBucketKeysForAttributes(Map<String, String> attributes) {
            Map<String, String> routeAttributes = approvedAttributes(attributes);
            if (routeAttributes.isEmpty()) {
                return Set.of(WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY);
            }
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            keys.add(WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY);
            for (Map<String, String> combination : combinations(routeAttributes)) {
                keys.add(bucketKey(combination));
            }
            return Set.copyOf(keys);
        }

        public String exactRouteBucketKeyForAttributes(Map<String, String> attributes) {
            Map<String, String> routeAttributes = approvedAttributes(attributes);
            return routeAttributes.isEmpty()
                    ? WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY
                    : bucketKey(routeAttributes);
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
            return first ? WorkerRouteBucketPolicy.DEFAULT_ROUTE_BUCKET_KEY : builder.toString();
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
