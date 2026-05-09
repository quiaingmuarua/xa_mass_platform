package com.xa.mass.sdk.model;

import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.enums.task.TaskSourceType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MassTaskShellCreateRequest {

    private final String userId;
    private final String tenantId;
    private final String project;
    private final Map<String, Object> sharedConfig;
    private final TaskExecutionSpec executionSpec;
    private final TaskSourceType sourceType;
    private final String sourceRef;

    private MassTaskShellCreateRequest(Builder builder) {
        this.userId = builder.userId;
        this.tenantId = normalizeString(builder.tenantId);
        this.project = builder.project;
        this.sharedConfig = unmodifiableMapCopy(builder.sharedConfig);
        this.executionSpec = TaskExecutionSpec.normalized(builder.executionSpec);
        this.sourceType = builder.sourceType;
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

    @Deprecated(forRemoval = false)
    public String getTaskName() {
        return null;
    }

    @Deprecated(forRemoval = false)
    public String getEventCode() {
        return null;
    }

    @Deprecated(forRemoval = false)
    public com.xa.mass.sdk.catalog.TaskMode getMode() {
        return com.xa.mass.sdk.catalog.TaskMode.SINGLE_RUN;
    }

    @Deprecated(forRemoval = false)
    public com.xa.mass.sdk.catalog.PayloadType getPayloadType() {
        return com.xa.mass.sdk.catalog.PayloadType.JSON;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public TaskExecutionSpec getExecutionSpec() {
        return executionSpec;
    }

    public int getBatchSize() {
        return executionSpec.getBatchSize();
    }

    public TaskSourceType getSourceType() {
        return sourceType;
    }

    public com.xa.mass.base.enums.task.TaskWorkloadClass getWorkloadClass() {
        return executionSpec.getWorkloadClass();
    }

    public int getMaxRuntimeSeconds() {
        return executionSpec.getMaxRuntimeSeconds();
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public static final class Builder {
        private String userId;
        private String tenantId;
        private String project;
        private Map<String, Object> sharedConfig = Collections.emptyMap();
        private TaskExecutionSpec executionSpec;
        private TaskSourceType sourceType;
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

        @Deprecated(forRemoval = false)
        public Builder taskName(String taskName) {
            return this;
        }

        @Deprecated(forRemoval = false)
        public Builder eventCode(String eventCode) {
            return this;
        }

        @Deprecated(forRemoval = false)
        public Builder mode(com.xa.mass.sdk.catalog.TaskMode mode) {
            return this;
        }

        @Deprecated(forRemoval = false)
        public Builder payloadType(com.xa.mass.sdk.catalog.PayloadType payloadType) {
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

        public Builder batchSize(int batchSize) {
            if (this.executionSpec == null) {
                this.executionSpec = new TaskExecutionSpec();
            }
            this.executionSpec.setBatchSize(batchSize);
            return this;
        }

        public Builder sourceType(TaskSourceType sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder workloadClass(com.xa.mass.base.enums.task.TaskWorkloadClass workloadClass) {
            if (this.executionSpec == null) {
                this.executionSpec = new TaskExecutionSpec();
            }
            this.executionSpec.setWorkloadClass(workloadClass);
            return this;
        }

        public Builder maxRuntimeSeconds(int maxRuntimeSeconds) {
            if (this.executionSpec == null) {
                this.executionSpec = new TaskExecutionSpec();
            }
            this.executionSpec.setMaxRuntimeSeconds(maxRuntimeSeconds);
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
