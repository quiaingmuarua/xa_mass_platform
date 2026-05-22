package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "TaskUpdateApiRequest", description = "Updates editable task shell fields for NEW or BLOCKED tasks.")
public class TaskUpdateApiRequest extends AbstractUnknownFieldRequest {

    @Schema(description = "Replacement owner user id", example = "agent")
    private String userId;
    @Schema(description = "Replacement project code", example = "demoApp")
    private String project;
    @Schema(description = "Replacement shared config. taskName and item payloads are not patched here.")
    private Map<String, Object> sharedConfig;

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
}
