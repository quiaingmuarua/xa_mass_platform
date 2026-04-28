package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.Map;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerContextRegisterApiRequest extends AbstractUnknownFieldRequest {

    private String workerContextId;
    private String workerId;
    private String project;
    private Set<String> routingTags;
    private Map<String, String> attributes;

    public String getWorkerContextId() {
        return workerContextId;
    }

    public void setWorkerContextId(String workerContextId) {
        this.workerContextId = workerContextId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public Set<String> getRoutingTags() {
        return routingTags;
    }

    public void setRoutingTags(Set<String> routingTags) {
        this.routingTags = routingTags;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }
}
