package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum RuntimeCommandStatus {
    OK("ok"),
    NOOP("noop"),
    REJECTED("rejected"),
    NOT_FOUND("not_found"),
    STALE("stale"),
    CONFLICT("conflict"),
    INVALID("invalid"),
    CREATED("created"),
    RETRYABLE("retryable"),
    APPROVED("approved"),
    ALREADY_APPROVED("already_approved"),
    CLOSED("closed"),
    ALREADY_CLOSED("already_closed"),
    APPENDED("appended");

    private final String wireValue;

    RuntimeCommandStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static RuntimeCommandStatus fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown runtime command status: " + value
                ));
    }
}
