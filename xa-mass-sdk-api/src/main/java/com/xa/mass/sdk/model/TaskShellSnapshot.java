package com.xa.mass.sdk.model;

public final class TaskShellSnapshot {

    private final String taskId;
    private final String taskName;
    private final String tenantId;
    private final String project;
    private final String userId;
    private final String contract;
    private final String sourceRef;

    public TaskShellSnapshot(String taskId,
                             String taskName,
                             String tenantId,
                             String project,
                             String userId,
                             String contract,
                             String sourceRef) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.tenantId = tenantId;
        this.project = project;
        this.userId = userId;
        this.contract = contract;
        this.sourceRef = sourceRef;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getProject() {
        return project;
    }

    public String getUserId() {
        return userId;
    }

    public String getContract() {
        return contract;
    }

    public String getSourceRef() {
        return sourceRef;
    }
}
