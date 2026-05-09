package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.enums.task.TaskSourceType;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskShellCreateApiRequest extends AbstractUnknownFieldRequest {

    private String userId;
    private String project;
    private Map<String, Object> sharedConfig;
    private TaskExecutionSpec executionSpec;
    private TaskSourceType sourceType;
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
        this.executionSpec = executionSpec;
    }

    public int getBatchSize() {
        return executionSpec == null ? 0 : executionSpec.getBatchSize();
    }

    public void setBatchSize(int batchSize) {
        if (this.executionSpec == null) {
            this.executionSpec = new TaskExecutionSpec();
        }
        this.executionSpec.setBatchSize(batchSize);
    }

    public TaskSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(TaskSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public int getMaxRuntimeSeconds() {
        return executionSpec == null ? 0 : executionSpec.getMaxRuntimeSeconds();
    }

    public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
        if (this.executionSpec == null) {
            this.executionSpec = new TaskExecutionSpec();
        }
        this.executionSpec.setMaxRuntimeSeconds(maxRuntimeSeconds);
    }

    public com.xa.mass.base.enums.task.TaskWorkloadClass getWorkloadClass() {
        return executionSpec == null ? null : executionSpec.getWorkloadClass();
    }

    public void setWorkloadClass(com.xa.mass.base.enums.task.TaskWorkloadClass workloadClass) {
        if (this.executionSpec == null) {
            this.executionSpec = new TaskExecutionSpec();
        }
        this.executionSpec.setWorkloadClass(workloadClass);
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }
}
