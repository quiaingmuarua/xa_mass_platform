package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerCapabilityReportApiRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private Long capabilityVersion;
    private List<String> availableEventCodes;
    private Map<String, String> schedulingAttributes;
    private String agentVersion;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Long getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(Long capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public List<String> getAvailableEventCodes() {
        return availableEventCodes;
    }

    public void setAvailableEventCodes(List<String> availableEventCodes) {
        this.availableEventCodes = availableEventCodes;
    }

    public Map<String, String> getSchedulingAttributes() {
        return schedulingAttributes;
    }

    public void setSchedulingAttributes(Map<String, String> schedulingAttributes) {
        this.schedulingAttributes = schedulingAttributes;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }
}
