package com.xa.mass.sdk.model;

import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MassTaskShellCreateRequest {

    private final String userId;
    private final String project;
    private final String taskName;
    private final String eventCode;
    private final TaskMode mode;
    private final PayloadType payloadType;
    private final Map<String, Object> sharedConfig;
    private final int batchSize;
    private final int maxRuntimeSeconds;
    private final TaskSourceType sourceType;
    private final TaskWorkloadClass workloadClass;
    private final String sourceRef;

    private MassTaskShellCreateRequest(Builder builder) {
        this.userId = builder.userId;
        this.project = builder.project;
        this.taskName = builder.taskName;
        this.eventCode = normalizeString(builder.eventCode);
        this.mode = builder.mode != null ? builder.mode : TaskMode.SINGLE_RUN;
        this.payloadType = builder.payloadType != null ? builder.payloadType : PayloadType.JSON;
        this.sharedConfig = unmodifiableMapCopy(builder.sharedConfig);
        this.batchSize = builder.batchSize;
        this.maxRuntimeSeconds = builder.maxRuntimeSeconds;
        this.sourceType = builder.sourceType;
        this.workloadClass = builder.workloadClass;
        this.sourceRef = normalizeString(builder.sourceRef);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getProject() {
        return project;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getEventCode() {
        return eventCode;
    }

    public TaskMode getMode() {
        return mode;
    }

    public PayloadType getPayloadType() {
        return payloadType;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public TaskSourceType getSourceType() {
        return sourceType;
    }

    public TaskWorkloadClass getWorkloadClass() {
        return workloadClass;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public static final class Builder {
        private String userId;
        private String project;
        private String taskName;
        private String eventCode;
        private TaskMode mode = TaskMode.SINGLE_RUN;
        private PayloadType payloadType = PayloadType.JSON;
        private Map<String, Object> sharedConfig = Collections.emptyMap();
        private int batchSize;
        private int maxRuntimeSeconds;
        private TaskSourceType sourceType;
        private TaskWorkloadClass workloadClass;
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

        public Builder taskName(String taskName) {
            this.taskName = taskName;
            return this;
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder mode(TaskMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder payloadType(PayloadType payloadType) {
            this.payloadType = payloadType;
            return this;
        }

        public Builder sharedConfig(Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig != null ? sharedConfig : Collections.emptyMap();
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder maxRuntimeSeconds(int maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
            return this;
        }

        public Builder sourceType(TaskSourceType sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder workloadClass(TaskWorkloadClass workloadClass) {
            this.workloadClass = workloadClass;
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
