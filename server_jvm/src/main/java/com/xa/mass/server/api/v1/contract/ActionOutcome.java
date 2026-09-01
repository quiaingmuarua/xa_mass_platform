package com.xa.mass.server.api.v1.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionOutcome(
        Status status,
        @Nullable Integer code,
        @Nullable String message
) {

    public ActionOutcome {
        Objects.requireNonNull(status, "status");
        if (status == Status.REJECTED) {
            if (code == null || message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "rejected outcome requires code and message"
                );
            }
        } else if (code != null || message != null) {
            throw new IllegalArgumentException(
                    "successful outcome cannot contain an error"
            );
        }
    }

    public static ActionOutcome applied() {
        return new ActionOutcome(Status.APPLIED, null, null);
    }

    public static ActionOutcome unchanged() {
        return new ActionOutcome(Status.UNCHANGED, null, null);
    }

    public static ActionOutcome rejected(ServerErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new ActionOutcome(
                Status.REJECTED,
                errorCode.code(),
                errorCode.defaultMessage()
        );
    }

    public enum Status {
        APPLIED("applied"),
        UNCHANGED("unchanged"),
        REJECTED("rejected");

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
