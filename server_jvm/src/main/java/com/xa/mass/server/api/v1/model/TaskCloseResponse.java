package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

public record TaskCloseResponse(Status status) {

    public TaskCloseResponse {
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        CLOSED("closed"),
        ALREADY_CLOSED("already_closed");

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
