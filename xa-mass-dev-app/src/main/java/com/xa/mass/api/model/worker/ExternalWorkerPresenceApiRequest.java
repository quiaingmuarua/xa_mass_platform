package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerPresenceApiRequest extends AbstractUnknownFieldRequest {

    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
