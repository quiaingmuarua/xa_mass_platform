package com.xa.mass.client.task;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TaskCreateRequest(
        String userId,
        String project,
        TaskContract contract,
        Map<String, Object> sharedConfig,
        TaskExecutionSpec executionSpec,
        String sourceRef
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId;
        private String project;
        private TaskContract contract;
        private Map<String, Object> sharedConfig = new LinkedHashMap<>();
        private TaskExecutionSpec executionSpec;
        private String sourceRef;

        private Builder() {
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder contract(TaskContract contract) {
            this.contract = contract;
            return this;
        }

        public Builder sharedConfig(Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sharedConfig);
            return this;
        }

        public Builder sharedConfig(String key, Object value) {
            if (value == null) {
                this.sharedConfig.remove(key);
            } else {
                this.sharedConfig.put(key, value);
            }
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.sharedConfig.remove(TaskSharedConfigKeys.WORKER_GROUP_IDS);
            putStringOrRemove(TaskSharedConfigKeys.WORKER_GROUP_ID, workerGroupId);
            return this;
        }

        public Builder workerGroupIds(Collection<String> workerGroupIds) {
            this.sharedConfig.remove(TaskSharedConfigKeys.WORKER_GROUP_ID);
            List<String> normalized = normalizeStringList(workerGroupIds);
            if (normalized.isEmpty()) {
                this.sharedConfig.remove(TaskSharedConfigKeys.WORKER_GROUP_IDS);
            } else {
                this.sharedConfig.put(TaskSharedConfigKeys.WORKER_GROUP_IDS, normalized);
            }
            return this;
        }

        public Builder targetWorkerAttribute(String key, String value) {
            return mergeStringAttribute(TaskSharedConfigKeys.TARGET_WORKER_ATTRIBUTES, key, value);
        }

        public Builder targetWorkerAttributes(Map<String, String> attributes) {
            return replaceStringAttributes(TaskSharedConfigKeys.TARGET_WORKER_ATTRIBUTES, attributes);
        }

        public Builder routingCode(String routingCode) {
            putStringOrRemove(TaskSharedConfigKeys.ROUTING_CODE, routingCode);
            return this;
        }

        public Builder routeAttribute(String key, String value) {
            return mergeStringAttribute(TaskSharedConfigKeys.ROUTE_ATTRIBUTES, key, value);
        }

        public Builder routeAttributes(Map<String, String> attributes) {
            return replaceStringAttributes(TaskSharedConfigKeys.ROUTE_ATTRIBUTES, attributes);
        }

        public Builder executionSpec(TaskExecutionSpec executionSpec) {
            this.executionSpec = executionSpec;
            return this;
        }

        public Builder sourceRef(String sourceRef) {
            this.sourceRef = sourceRef;
            return this;
        }

        public TaskCreateRequest build() {
            return new TaskCreateRequest(userId, project, contract, Map.copyOf(sharedConfig), executionSpec, sourceRef);
        }

        private void putStringOrRemove(String key, String value) {
            if (value == null || value.isBlank()) {
                this.sharedConfig.remove(key);
            } else {
                this.sharedConfig.put(key, value);
            }
        }

        private Builder mergeStringAttribute(String sharedConfigKey, String key, String value) {
            requireAttributeKey(key);
            Map<String, String> attributes = copyExistingStringAttributes(sharedConfig.get(sharedConfigKey));
            if (value == null || value.isBlank()) {
                attributes.remove(key);
            } else {
                attributes.put(key, value);
            }
            if (attributes.isEmpty()) {
                sharedConfig.remove(sharedConfigKey);
            } else {
                sharedConfig.put(sharedConfigKey, Map.copyOf(attributes));
            }
            return this;
        }

        private Builder replaceStringAttributes(String sharedConfigKey, Map<String, String> attributes) {
            Map<String, String> normalized = normalizeStringMap(attributes);
            if (normalized.isEmpty()) {
                sharedConfig.remove(sharedConfigKey);
            } else {
                sharedConfig.put(sharedConfigKey, Map.copyOf(normalized));
            }
            return this;
        }

        private static List<String> normalizeStringList(Collection<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }

        private static Map<String, String> normalizeStringMap(Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                requireAttributeKey(key);
                if (value != null && !value.isBlank()) {
                    normalized.put(key, value);
                }
            });
            return normalized;
        }

        private static Map<String, String> copyExistingStringAttributes(Object value) {
            if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
                return new LinkedHashMap<>();
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            raw.forEach((key, attributeValue) -> {
                if (key instanceof String stringKey && !stringKey.isBlank()
                        && attributeValue instanceof String stringValue && !stringValue.isBlank()) {
                    attributes.put(stringKey, stringValue);
                }
            });
            return attributes;
        }

        private static void requireAttributeKey(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("attribute key is required");
            }
        }
    }
}
