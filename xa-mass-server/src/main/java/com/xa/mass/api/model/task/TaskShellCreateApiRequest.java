package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "TaskShellCreateApiRequest", description = "Creates a task shell only. Work items must be appended separately.")
public class TaskShellCreateApiRequest extends AbstractUnknownFieldRequest {

    @Schema(description = "Task owner user id. Required for operator calls; submitter credentials may resolve it.", example = "agent")
    private String userId;
    @Schema(description = "Project code that owns the task. Event authorization is resolved through project grants.", example = "demoApp")
    private String project;
    @Schema(description = "Task runtime contract", allowableValues = {"SESSION", "BATCH"}, example = "BATCH")
    private String contract;
    @Schema(description = "Task-level shared config. eventCode is not task truth; use this only for agreed shared context.")
    private Map<String, Object> sharedConfig;
    @Schema(description = "Task execution policy. Do not include contract here; contract is top-level.")
    private TaskExecutionOptions executionSpec;
    @Schema(description = "Optional external source reference", example = "import://demo/seed.ndjson")
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
