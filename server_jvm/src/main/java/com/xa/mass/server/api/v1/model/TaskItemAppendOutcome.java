package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskItemAppendOutcome(
        Status status,
        @Nullable Integer code,
        @Nullable String message
) {

    public TaskItemAppendOutcome {
        Objects.requireNonNull(status, "status");
        if (status == Status.SUCCEEDED && (code != null || message != null)) {
            throw new IllegalArgumentException(
                    "succeeded outcome cannot contain an error"
            );
        }
        if (status == Status.FAILED && (code == null || message == null)) {
            throw new IllegalArgumentException(
                    "failed outcome requires code and message"
            );
        }
    }

    public static TaskItemAppendOutcome succeeded() {
        return new TaskItemAppendOutcome(Status.SUCCEEDED, null, null);
    }

    public static TaskItemAppendOutcome failed(ServerErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new TaskItemAppendOutcome(
                Status.FAILED,
                errorCode.code(),
                errorCode.defaultMessage()
        );
    }

    public enum Status {
        SUCCEEDED("succeeded"),
        FAILED("failed");

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
