package com.xa.mass.api.model.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class SdkTaskCreateApiRequest extends AbstractUnknownFieldRequest {

    private String userId;
    private String project;
    private String taskName;
    private String eventCode;
    private TaskMode mode = TaskMode.SINGLE_RUN;
    private PayloadType payloadType = PayloadType.JSON;
    private Map<String, Object> sharedConfig;
    private List<Object> inputs;
    private String routingCode;
    private int batchSize;
    private int defaultMsgMaxRetryCount = 3;
    private int maxRuntimeSeconds;

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

    public List<Object> getInputs() {
        return inputs;
    }

    public void setInputs(List<Object> inputs) {
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
