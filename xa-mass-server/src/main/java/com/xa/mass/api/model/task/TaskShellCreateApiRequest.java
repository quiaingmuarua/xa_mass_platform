package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.sdk.model.TaskExecutionOptions;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskShellCreateApiRequest extends AbstractUnknownFieldRequest {

    private String userId;
    private String project;
    private String contract;
    private Map<String, Object> sharedConfig;
    private TaskExecutionOptions executionSpec;
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

    public String getContract() {
        return contract;
    }

    public void setContract(String contract) {
        this.contract = contract;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public void setSharedConfig(Map<String, Object> sharedConfig) {
        this.sharedConfig = sharedConfig;
    }

    public TaskExecutionOptions getExecutionSpec() {
        return executionSpec;
    }

    public void setExecutionSpec(TaskExecutionOptions executionSpec) {
        this.executionSpec = executionSpec;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }
}
