package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerRegisterApiRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private String workerGroupId;
    private String adapterId;
    private String transportHint;
    private Map<String, String> attributes;
    private List<ExternalWorkerEventBindingApiRequest> eventBindings;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public void setWorkerGroupId(String workerGroupId) {
        this.workerGroupId = workerGroupId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public void setAdapterId(String adapterId) {
        this.adapterId = adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public void setTransportHint(String transportHint) {
        this.transportHint = transportHint;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public List<ExternalWorkerEventBindingApiRequest> getEventBindings() {
        return eventBindings;
    }

    public void setEventBindings(List<ExternalWorkerEventBindingApiRequest> eventBindings) {
        this.eventBindings = eventBindings;
    }
}
