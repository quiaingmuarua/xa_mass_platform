package com.xa.mass.sdk.model;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.model.TaskExecutionSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MassTaskShellCreateRequest {

    private final String userId;
    private final String tenantId;
    private final String project;
    private final TaskContract contract;
    private final Map<String, Object> sharedConfig;
    private final TaskExecutionSpec executionSpec;
    private final String sourceRef;

    private MassTaskShellCreateRequest(Builder builder) {
        this.userId = builder.userId;
        this.tenantId = normalizeString(builder.tenantId);
        this.project = builder.project;
        this.contract = builder.contract;
        this.sharedConfig = unmodifiableMapCopy(builder.sharedConfig);
        this.executionSpec = TaskExecutionSpec.normalized(builder.executionSpec);
        this.sourceRef = normalizeString(builder.sourceRef);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getProject() {
        return project;
    }

    public TaskContract getContract() {
        return contract;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public TaskExecutionSpec getExecutionSpec() {
        return executionSpec;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public static final class Builder {
        private String userId;
        private String tenantId;
        private String project;
        private TaskContract contract;
        private Map<String, Object> sharedConfig = Collections.emptyMap();
        private TaskExecutionSpec executionSpec;
        private String sourceRef;

        private Builder() {
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
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
            this.sharedConfig = sharedConfig != null ? sharedConfig : Collections.emptyMap();
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

        public MassTaskShellCreateRequest build() {
            return new MassTaskShellCreateRequest(this);
        }
    }

    private static Map<String, Object> unmodifiableMapCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalizeString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
