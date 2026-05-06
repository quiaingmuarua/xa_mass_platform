package com.xa.mass.base.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskWorkloadClass;

import java.util.List;
import java.util.Map;

/**
 * Shared task-create contract used across engine, SDK, server bootstrap, and
 * testing flows.
 *
 * <p>This type is intentionally kept in the neutral base module because task
 * creation is not an engine-private concern. Runtime shells and acceptance
 * harnesses should not need to depend on engine package ownership just to
 * submit task-create input.
 *
 * <p>Unknown JSON properties are rejected ({@code ignoreUnknown = false}) so
 * outdated clients fail fast instead of silently ignoring unsupported fields.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskCreateRequestDto {

    private String userId;
    private String project;
    private String taskName;
    private Map<String, Object> sharedConfig;
    private List<Map<String, Object>> inputs;
    private int batchSize;
    private int defaultMsgMaxRetryCount = 3;
    private boolean openEnded = false;
    private int maxRuntimeSeconds = 0;
    private TaskSourceType sourceType;
    private TaskWorkloadClass workloadClass;
    private String sourceRef;

    public TaskCreateRequestDto() {
    }

    public TaskCreateRequestDto(String userId,
                                String project,
                                String taskName,
                                Map<String, Object> sharedConfig,
                                List<Map<String, Object>> inputs) {
        this.userId = userId;
        this.project = project;
        this.taskName = taskName;
        this.sharedConfig = sharedConfig;
        this.inputs = inputs;
    }

    public boolean isOpenEnded() {
        return openEnded;
    }

    public void setOpenEnded(boolean openEnded) {
        this.openEnded = openEnded;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public void setSharedConfig(Map<String, Object> sharedConfig) {
        this.sharedConfig = sharedConfig;
    }

    public List<Map<String, Object>> getInputs() {
        return inputs;
    }

    public void setInputs(List<Map<String, Object>> inputs) {
        this.inputs = inputs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getDefaultMsgMaxRetryCount() {
        return defaultMsgMaxRetryCount;
    }

    public void setDefaultMsgMaxRetryCount(int defaultMsgMaxRetryCount) {
        this.defaultMsgMaxRetryCount = defaultMsgMaxRetryCount;
    }

    public int getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
        this.maxRuntimeSeconds = maxRuntimeSeconds;
    }

    public TaskSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(TaskSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public TaskWorkloadClass getWorkloadClass() {
        return workloadClass;
    }

    public void setWorkloadClass(TaskWorkloadClass workloadClass) {
        this.workloadClass = workloadClass;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }
}
