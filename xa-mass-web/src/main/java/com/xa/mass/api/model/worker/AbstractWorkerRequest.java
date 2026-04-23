package com.xa.mass.api.model.worker;

import com.xa.mass.api.model.AbstractUnknownFieldRequest;

public abstract class AbstractWorkerRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private String project;

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
}
