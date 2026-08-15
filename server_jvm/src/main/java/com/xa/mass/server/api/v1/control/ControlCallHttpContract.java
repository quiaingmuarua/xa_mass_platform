package com.xa.mass.server.api.v1.control;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class ControlCallHttpContract {

    private ControlCallHttpContract() {
    }

    public record WorkerControlBatchCallRequest(
            @NotEmpty @Size(max = 100)
            List<@NotBlank String> workerIds,
            @NotBlank String messageType,
            @NotNull String opaquePayload,
            @Positive @Max(10_000) Long waitTimeoutMillis
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Worker Control Batch field: " + name
            );
        }
    }

    public record AdapterControlCallRequest(
            @NotBlank String messageType,
            @NotNull String opaquePayload,
            @Positive @Max(10_000) Long waitTimeoutMillis
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Adapter Control Call field: " + name
            );
        }
    }

    public record ControlBatchCallResponse(
            String controlBatchId,
            ControlBatchStatus status,
            Map<String, ControlTargetCallResponse> results
    ) {
        public ControlBatchCallResponse {
            results = Collections.unmodifiableMap(
                    new LinkedHashMap<>(results)
            );
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ControlTargetCallResponse(
            ControlTargetStatus status,
            @Nullable String outcomeCode,
            @Nullable String opaqueResultPayload,
            @Nullable ControlTargetReason reason
    ) {
        public static ControlTargetCallResponse observed(
                String outcomeCode,
                String payload
        ) {
            return new ControlTargetCallResponse(
                    ControlTargetStatus.OBSERVED,
                    outcomeCode,
                    payload,
                    null
            );
        }

        public static ControlTargetCallResponse unobserved(
                ControlTargetReason reason
        ) {
            return new ControlTargetCallResponse(
                    ControlTargetStatus.UNOBSERVED,
                    null,
                    null,
                    reason
            );
        }

        public static ControlTargetCallResponse rejected(
                ControlTargetReason reason
        ) {
            return new ControlTargetCallResponse(
                    ControlTargetStatus.REJECTED,
                    null,
                    null,
                    reason
            );
        }
    }

    public enum ControlBatchStatus {
        OBSERVED("observed"),
        PARTIAL("partial");

        private final String wireValue;

        ControlBatchStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum ControlTargetStatus {
        OBSERVED("observed"),
        UNOBSERVED("unobserved"),
        REJECTED("rejected");

        private final String wireValue;

        ControlTargetStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum ControlTargetReason {
        TIMEOUT("timeout"),
        REPLACED("replaced"),
        SHUTDOWN("shutdown"),
        NOT_FOUND("not-found"),
        CONTROL_ONLY_REQUIRED("control-only-required"),
        SCORE_UNAVAILABLE("score-unavailable"),
        NOT_BOUND("not-bound"),
        ENDPOINT_UNAVAILABLE("endpoint-unavailable"),
        POLLING_ENDPOINT("polling-endpoint");

        private final String wireValue;

        ControlTargetReason(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }
}
