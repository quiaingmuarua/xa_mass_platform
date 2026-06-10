package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerPresenceApiRequest extends AbstractUnknownFieldRequest {

    private String reason;
    private String sessionToken;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }
}
