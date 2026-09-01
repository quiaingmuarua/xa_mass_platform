package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskItemResultStatus {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    NOT_OBSERVED("not_observed");

    private final String wireValue;

    TaskItemResultStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
