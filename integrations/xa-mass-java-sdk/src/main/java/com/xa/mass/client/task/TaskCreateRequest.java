package com.xa.mass.client.task;

import java.util.LinkedHashMap;
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
            this.sharedConfig.put(key, value);
            return this;
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
    }
}
