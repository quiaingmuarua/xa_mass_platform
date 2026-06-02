package com.xa.mass.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.Locale;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskExecutionOptions {

    private String profile;
    private String workloadClass;
    private int batchSize;
    private int maxRuntimeSeconds;
    private int defaultMaxRetryCount;
    private boolean foreground;

    public TaskExecutionOptions() {
        this.profile = "STANDARD";
        this.workloadClass = null;
        this.batchSize = 1;
        this.maxRuntimeSeconds = 0;
        this.defaultMaxRetryCount = 0;
        this.foreground = true;
    }

    public static TaskExecutionOptions normalized(TaskExecutionOptions options) {
        TaskExecutionOptions normalized = new TaskExecutionOptions();
        if (options == null) {
            return normalized;
        }
        normalized.setProfile(options.getProfile());
        normalized.setWorkloadClass(options.getWorkloadClass());
        normalized.setBatchSize(options.getBatchSize());
        normalized.setMaxRuntimeSeconds(options.getMaxRuntimeSeconds());
        normalized.setDefaultMaxRetryCount(options.getDefaultMaxRetryCount());
        normalized.setForeground(options.isForeground());
        return normalized;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = normalizeToken(profile, "STANDARD");
    }

    public String getWorkloadClass() {
        return workloadClass;
    }

    public void setWorkloadClass(String workloadClass) {
        this.workloadClass = normalizeToken(workloadClass, null);
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

    public boolean isForeground() {
        return foreground;
    }

    public void setForeground(boolean foreground) {
        this.foreground = foreground;
    }

    @JsonSetter("contract")
    public void rejectLegacyContractField(Object ignored) {
        throw new IllegalArgumentException("executionSpec.contract has been removed; use top-level contract");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskExecutionOptions that)) return false;
        return batchSize == that.batchSize
                && maxRuntimeSeconds == that.maxRuntimeSeconds
                && defaultMaxRetryCount == that.defaultMaxRetryCount
                && foreground == that.foreground
                && Objects.equals(profile, that.profile)
                && Objects.equals(workloadClass, that.workloadClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profile, workloadClass, batchSize, maxRuntimeSeconds, defaultMaxRetryCount, foreground);
    }

    private static String normalizeToken(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
