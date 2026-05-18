package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskStageEvidenceApiRequest extends AbstractUnknownFieldRequest {

    private long stageVersion;
    private String stageStatus;
    private String detail;
    private Instant observedAt;
    private Map<String, Object> attributes;

    public long getStageVersion() {
        return stageVersion;
    }

    public void setStageVersion(long stageVersion) {
        this.stageVersion = stageVersion;
    }

    public String getStageStatus() {
        return stageStatus;
    }

    public void setStageStatus(String stageStatus) {
        this.stageStatus = stageStatus;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
