package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskShellCreateApiRequest extends AbstractUnknownFieldRequest {

    private String userId;
    private String project;
    private String taskName;
    private String eventCode;
    private TaskMode mode;
    private PayloadType payloadType;
    private Map<String, Object> sharedConfig;
    private int batchSize;
    private int maxRuntimeSeconds;
    private TaskSourceType sourceType;
    private TaskWorkloadClass workloadClass;
    private String sourceRef;

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

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public TaskMode getMode() {
        return mode;
    }

    public void setMode(TaskMode mode) {
        this.mode = mode;
    }

    public PayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(PayloadType payloadType) {
        this.payloadType = payloadType;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public void setSharedConfig(Map<String, Object> sharedConfig) {
        this.sharedConfig = sharedConfig;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
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
