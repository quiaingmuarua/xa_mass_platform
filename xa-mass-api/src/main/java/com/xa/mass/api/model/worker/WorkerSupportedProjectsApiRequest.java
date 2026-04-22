package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerSupportedProjectsApiRequest extends AbstractUnknownFieldRequest {

    private List<String> supportedProjects;

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public void setSupportedProjects(List<String> supportedProjects) {
        this.supportedProjects = supportedProjects;
    }
}
