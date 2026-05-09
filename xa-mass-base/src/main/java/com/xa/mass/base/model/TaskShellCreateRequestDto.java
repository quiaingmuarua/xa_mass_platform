package com.xa.mass.base.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.base.enums.task.TaskContract;

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

    @Deprecated(forRemoval = false)
    public String getTaskName() {
        return null;
    }

    @Deprecated(forRemoval = false)
    public void setTaskName(String taskName) {
        // taskName is server-derived; legacy callers are ignored.
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
        return executionSpec;
    }

    public void setExecutionSpec(TaskExecutionSpec executionSpec) {
        this.executionSpec = TaskExecutionSpec.normalized(executionSpec);
    }

    public TaskContract getContract() {
        return executionSpecOrDefault().getContract();
    }

    public void setContract(TaskContract contract) {
        executionSpecOrDefault().setContract(contract);
    }

    public int getBatchSize() {
        return executionSpecOrDefault().getBatchSize();
    }

    public void setBatchSize(int batchSize) {
        executionSpecOrDefault().setBatchSize(batchSize);
    }

    public int getMaxRuntimeSeconds() {
        return executionSpecOrDefault().getMaxRuntimeSeconds();
    }

    public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
        executionSpecOrDefault().setMaxRuntimeSeconds(maxRuntimeSeconds);
    }

    public int getDefaultMaxRetryCount() {
        return executionSpecOrDefault().getDefaultMaxRetryCount();
    }

    public void setDefaultMaxRetryCount(int defaultMaxRetryCount) {
        executionSpecOrDefault().setDefaultMaxRetryCount(defaultMaxRetryCount);
    }

    public com.xa.mass.base.enums.task.TaskWorkloadClass getWorkloadClass() {
        return executionSpecOrDefault().getWorkloadClass();
    }

    public void setWorkloadClass(com.xa.mass.base.enums.task.TaskWorkloadClass workloadClass) {
        executionSpecOrDefault().setWorkloadClass(workloadClass);
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
