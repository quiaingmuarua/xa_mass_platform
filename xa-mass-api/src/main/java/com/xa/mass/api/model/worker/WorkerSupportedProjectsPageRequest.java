package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerSupportedProjectsPageRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private List<String> supportedProjects;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public void setSupportedProjects(List<String> supportedProjects) {
        this.supportedProjects = supportedProjects;
    }
}
