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
    private List<String> targetList;
    private String countryCode;
    private int batchSize;
    private int defaultMsgMaxRetryCount = 3;
    private boolean openEnded = false;

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
                                List<String> targetList,
                                String countryCode) {
        this.userId = userId;
        this.project = project;
        this.taskName = taskName;
        this.sharedConfig = sharedConfig;
        this.targetList = targetList;
        this.countryCode = countryCode;
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

    public List<String> getTargetList() {
        return targetList;
    }

    public void setTargetList(List<String> targetList) {
        this.targetList = targetList;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
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
}
