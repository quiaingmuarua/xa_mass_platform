package com.xa.mass.server.api.v1.contract.delivery.directcall;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class DirectCallHttpContract {

    private DirectCallHttpContract() {
    }

    public record DirectCallRequest(
            @Nullable String workerGroupId,
            @Nullable @Size(min = 1, max = 100)
            Map<@NotBlank String, @NotNull String> workerPayloads,
            @NotBlank String messageType,
            @Nullable String opaquePayload,
            @Positive @Max(10_000) Long waitTimeoutMillis
    ) {
        public DirectCallRequest {
            if (workerPayloads != null) {
                workerPayloads = Collections.unmodifiableMap(
                        new LinkedHashMap<>(workerPayloads)
                );
            }
        }

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Direct Call field: " + name
            );
        }
    }

    public record DirectCallResponse(
            String directCallId,
            DirectCallStatus status,
            Map<String, DirectTargetCallResponse> results
    ) {
        public DirectCallResponse {
            results = Collections.unmodifiableMap(
                    new LinkedHashMap<>(results)
            );
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectTargetCallResponse(
            DirectTargetStatus status,
            @Nullable String outcomeCode,
            @Nullable String opaqueResultPayload,
            @Nullable DirectTargetReason reason
    ) {
        public static DirectTargetCallResponse observed(
                String outcomeCode,
                String payload
        ) {
            return new DirectTargetCallResponse(
                    DirectTargetStatus.OBSERVED,
                    outcomeCode,
                    payload,
                    null
            );
        }

        public static DirectTargetCallResponse unobserved(
                DirectTargetReason reason
        ) {
            return new DirectTargetCallResponse(
                    DirectTargetStatus.UNOBSERVED,
                    null,
                    null,
                    reason
            );
        }

        public static DirectTargetCallResponse rejected(
                DirectTargetReason reason
        ) {
            return new DirectTargetCallResponse(
                    DirectTargetStatus.REJECTED,
                    null,
                    null,
                    reason
            );
        }
    }

    public enum DirectCallStatus {
        OBSERVED("observed"),
        PARTIAL("partial");

        private final String wireValue;

        DirectCallStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum DirectTargetStatus {
        OBSERVED("observed"),
        UNOBSERVED("unobserved"),
        REJECTED("rejected");

        private final String wireValue;

        DirectTargetStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum DirectTargetReason {
        TIMEOUT("timeout"),
        SHUTDOWN("shutdown"),
        NOT_FOUND("not-found"),
        NOT_BOUND("not-bound"),
        ENDPOINT_MISMATCH("endpoint-mismatch"),
        COMMAND_SLOT_OCCUPIED("command-slot-occupied"),
        SUBMISSION_UNKNOWN("submission-unknown");

        private final String wireValue;

        DirectTargetReason(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }
}
