package com.xa.mass.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Supported task-create contract for the current runtime.
 *
 * <p>This DTO intentionally exposes only fields that are implemented by the
 * active mainline. Unknown JSON properties are rejected so outdated clients and
 * agents fail fast instead of assuming unsupported behavior exists.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskCreateRequestDto {

    private String userId;
    private String project;
    private String taskName;
    private Map<String, Object> sharedConfig;
    private List<Map<String, Object>> inputs;
    private String routingCode;
    private int batchSize;
    private int defaultMsgMaxRetryCount = 3;
    private boolean openEnded = false;
    private int maxRuntimeSeconds = 0;

    public TaskCreateRequestDto() {
    }

    public boolean isOpenEnded() {
        return openEnded;
    }

    public void setOpenEnded(boolean openEnded) {
        this.openEnded = openEnded;
    }

    public TaskCreateRequestDto(String userId,
                                String project,
                                String taskName,
                                Map<String, Object> sharedConfig,
                                List<Map<String, Object>> inputs,
                                String routingCode) {
        this.userId = userId;
        this.project = project;
        this.taskName = taskName;
        this.sharedConfig = sharedConfig;
        this.inputs = inputs;
        this.routingCode = routingCode;
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

    public String getRoutingCode() {
        return routingCode;
    }

    public void setRoutingCode(String routingCode) {
        this.routingCode = routingCode;
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
}
