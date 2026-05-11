package com.xa.mass.base.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.xa.mass.base.enums.task.TaskExecutionProfile;
import com.xa.mass.base.enums.task.TaskWorkloadClass;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskExecutionSpec {

    private TaskExecutionProfile profile;
    private TaskWorkloadClass workloadClass;
    private int batchSize;
    private int maxRuntimeSeconds;
    private int defaultMaxRetryCount;

    public TaskExecutionSpec() {
        this.profile = TaskExecutionProfile.STANDARD;
        this.workloadClass = null;
        this.batchSize = 1;
        this.maxRuntimeSeconds = 0;
        this.defaultMaxRetryCount = 0;
    }

    public static TaskExecutionSpec normalized(TaskExecutionSpec spec) {
        TaskExecutionSpec normalized = new TaskExecutionSpec();
        if (spec == null) {
            return normalized;
        }
        normalized.setProfile(spec.getProfile());
        normalized.setWorkloadClass(spec.getWorkloadClass());
        normalized.setBatchSize(spec.getBatchSize());
        normalized.setMaxRuntimeSeconds(spec.getMaxRuntimeSeconds());
        normalized.setDefaultMaxRetryCount(spec.getDefaultMaxRetryCount());
        return normalized;
    }

    public TaskExecutionProfile getProfile() {
        return profile;
    }

    public void setProfile(TaskExecutionProfile profile) {
        this.profile = profile == null ? TaskExecutionProfile.STANDARD : profile;
    }

    public TaskWorkloadClass getWorkloadClass() {
        return workloadClass;
    }

    public void setWorkloadClass(TaskWorkloadClass workloadClass) {
        this.workloadClass = workloadClass;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(batchSize, 1);
    }

    public int getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
        this.maxRuntimeSeconds = Math.max(maxRuntimeSeconds, 0);
    }

    public int getDefaultMaxRetryCount() {
        return defaultMaxRetryCount;
    }

    public void setDefaultMaxRetryCount(int defaultMaxRetryCount) {
        this.defaultMaxRetryCount = Math.max(defaultMaxRetryCount, 0);
    }

    @JsonSetter("contract")
    public void rejectLegacyContractField(Object ignored) {
        throw new IllegalArgumentException("executionSpec.contract has been removed; use top-level contract");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskExecutionSpec that)) return false;
        return batchSize == that.batchSize
                && maxRuntimeSeconds == that.maxRuntimeSeconds
                && defaultMaxRetryCount == that.defaultMaxRetryCount
                && profile == that.profile
                && workloadClass == that.workloadClass;
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, workloadClass, batchSize, maxRuntimeSeconds, defaultMaxRetryCount);
    }
}
