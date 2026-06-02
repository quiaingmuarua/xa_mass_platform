package com.xa.mass.contract.task;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.Locale;
import java.util.Objects;

public class TaskExecutionSpec {
    private String profile;
    private String workloadClass;
    private Integer batchSize;
    private Integer maxRuntimeSeconds;
    private Integer defaultMaxRetryCount;
    private Boolean foreground;

    public TaskExecutionSpec() {
    }

    public TaskExecutionSpec(String profile,
                             String workloadClass,
                             Integer batchSize,
                             Integer maxRuntimeSeconds,
                             Integer defaultMaxRetryCount,
                             Boolean foreground) {
        this.profile = profile;
        this.workloadClass = workloadClass;
        this.batchSize = batchSize;
        this.maxRuntimeSeconds = maxRuntimeSeconds;
        this.defaultMaxRetryCount = defaultMaxRetryCount;
        this.foreground = foreground;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = normalizeToken(profile);
    }

    public String getWorkloadClass() {
        return workloadClass;
    }

    public void setWorkloadClass(String workloadClass) {
        this.workloadClass = normalizeToken(workloadClass);
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize == null ? null : Math.max(batchSize, 1);
    }

    public Integer getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public void setMaxRuntimeSeconds(Integer maxRuntimeSeconds) {
        this.maxRuntimeSeconds = maxRuntimeSeconds == null ? null : Math.max(maxRuntimeSeconds, 0);
    }

    public Integer getDefaultMaxRetryCount() {
        return defaultMaxRetryCount;
    }

    public void setDefaultMaxRetryCount(Integer defaultMaxRetryCount) {
        this.defaultMaxRetryCount = defaultMaxRetryCount == null ? null : Math.max(defaultMaxRetryCount, 0);
    }

    public Boolean getForeground() {
        return foreground;
    }

    public void setForeground(Boolean foreground) {
        this.foreground = foreground;
    }

    public String profile() {
        return profile;
    }

    public String workloadClass() {
        return workloadClass;
    }

    public Integer batchSize() {
        return batchSize;
    }

    public Integer maxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public Integer defaultMaxRetryCount() {
        return defaultMaxRetryCount;
    }

    public Boolean foreground() {
        return foreground;
    }

    @JsonSetter("contract")
    public void rejectLegacyContractField(Object ignored) {
        throw new IllegalArgumentException("executionSpec.contract has been removed; use top-level contract");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskExecutionSpec that)) {
            return false;
        }
        return Objects.equals(profile, that.profile)
                && Objects.equals(workloadClass, that.workloadClass)
                && Objects.equals(batchSize, that.batchSize)
                && Objects.equals(maxRuntimeSeconds, that.maxRuntimeSeconds)
                && Objects.equals(defaultMaxRetryCount, that.defaultMaxRetryCount)
                && Objects.equals(foreground, that.foreground);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, workloadClass, batchSize, maxRuntimeSeconds, defaultMaxRetryCount, foreground);
    }

    private static String normalizeToken(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static final class Builder {
        private String profile;
        private String workloadClass;
        private Integer batchSize;
        private Integer maxRuntimeSeconds;
        private Integer defaultMaxRetryCount;
        private Boolean foreground;

        private Builder() {
        }

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder workloadClass(String workloadClass) {
            this.workloadClass = workloadClass;
            return this;
        }

        public Builder batchSize(Integer batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder maxRuntimeSeconds(Integer maxRuntimeSeconds) {
            this.maxRuntimeSeconds = maxRuntimeSeconds;
            return this;
        }

        public Builder defaultMaxRetryCount(Integer defaultMaxRetryCount) {
            this.defaultMaxRetryCount = defaultMaxRetryCount;
            return this;
        }

        public Builder foreground(Boolean foreground) {
            this.foreground = foreground;
            return this;
        }

        public TaskExecutionSpec build() {
            return new TaskExecutionSpec(profile, workloadClass, batchSize, maxRuntimeSeconds,
                    defaultMaxRetryCount, foreground);
        }
    }
}
