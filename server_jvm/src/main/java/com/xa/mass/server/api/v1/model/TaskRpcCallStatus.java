package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskRpcCallStatus {
    SUCCEEDED("succeeded"),
    PENDING("pending");

    private final String wireValue;

    TaskRpcCallStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
