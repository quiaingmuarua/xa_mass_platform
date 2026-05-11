package com.xa.mass.base.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskShellCreateRequestDto {

    private String userId;
    private String tenantId;
    private String project;
    private Map<String, Object> sharedConfig;
    private TaskExecutionSpec executionSpec;
    private String sourceRef;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public void setSharedConfig(Map<String, Object> sharedConfig) {
        this.sharedConfig = sharedConfig;
    }

    public TaskExecutionSpec getExecutionSpec() {
        return executionSpecOrDefault();
    }

    public void setExecutionSpec(TaskExecutionSpec executionSpec) {
        this.executionSpec = TaskExecutionSpec.normalized(executionSpec);
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    private TaskExecutionSpec executionSpecOrDefault() {
        if (this.executionSpec == null) {
            this.executionSpec = new TaskExecutionSpec();
        }
        return this.executionSpec;
    }
}
