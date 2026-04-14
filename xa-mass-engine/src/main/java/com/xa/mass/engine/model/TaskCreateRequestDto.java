package com.xa.mass.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

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
    private String textContent;
    private List<String> targetList;
    private String countryCode;
    private int batchSize;

    public TaskCreateRequestDto() {
    }

    public TaskCreateRequestDto(String userId,
                                String project,
                                String taskName,
                                String textContent,
                                List<String> targetList,
                                String countryCode) {
        this.userId = userId;
        this.project = project;
        this.taskName = taskName;
        this.textContent = textContent;
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

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
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
}
