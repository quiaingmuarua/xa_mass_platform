package com.xa.mass.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskWorkloadClass;

import java.util.List;
import java.util.Map;

/**
 * Engine-internal task-create contract.
 *
 * <p>This DTO is the authoritative input type for {@link com.xa.mass.engine.TaskManager#createTask}.
 * It lives in {@code xa-mass-engine} so the engine module has no dependency on
 * {@code xa-mass-sdk-api}. The SDK-facing equivalent is
 * {@code com.xa.mass.sdk.model.MassTaskCreateRequest}; {@code MassApplication} maps
 * between the two at the composition boundary.
 *
 * <p>Unknown JSON properties are rejected ({@code ignoreUnknown = false}) so
 * outdated REST clients fail fast instead of silently ignoring unsupported fields.
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
                                List<Map<String, Object>> inputs) {
        this.userId = userId;
        this.project = project;
        this.taskName = taskName;
        this.sharedConfig = sharedConfig;
        this.inputs = inputs;
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
