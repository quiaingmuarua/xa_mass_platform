package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

public record TaskApprovalResponse(Status status) {

    public TaskApprovalResponse {
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        APPROVED("approved"),
        ALREADY_APPROVED("already_approved");

        private final String wireValue;

        Status(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }
}
