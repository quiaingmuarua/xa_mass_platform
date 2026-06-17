package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerRuntimeEvidenceApiRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private Long evidenceVersion;
    private String state;
    private String reason;
    private Instant observedAt;
    private Map<String, String> attributes;

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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }
}
