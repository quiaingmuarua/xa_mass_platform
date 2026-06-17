package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerHandlerEvidenceApiRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private Long evidenceVersion;
    private List<String> eventCodes;
    private Map<String, String> attributes;
    private String agentVersion;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Long getEvidenceVersion() {
        return evidenceVersion;
    }

    public void setEvidenceVersion(Long evidenceVersion) {
        this.evidenceVersion = evidenceVersion;
    }

    public List<String> getEventCodes() {
        return eventCodes;
    }

    public void setEventCodes(List<String> eventCodes) {
        this.eventCodes = eventCodes;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }
}
