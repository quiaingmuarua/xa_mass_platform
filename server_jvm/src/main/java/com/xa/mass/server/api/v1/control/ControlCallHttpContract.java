package com.xa.mass.server.api.v1.control;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandResponse;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
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

    public record ControlCommandConsumeRequest(
            @Positive @Max(100) int limit
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Control Command consume field: " + name
            );
        }
    }

    public record ControlCommandConsumeResponse(
            Map<String, WorkerCommandResponse> commands
    ) {
        public static ControlCommandConsumeResponse from(
                Map<String, DeliveryCommand> source
        ) {
            Map<String, WorkerCommandResponse> response =
                    new LinkedHashMap<>();
            source.forEach((target, command) -> response.put(
                    target,
                    new WorkerCommandResponse(
                            command.src().wireValue(),
                            command.dst().wireValue(),
                            command.messageType(),
                            command.executeBeforeMillis(),
                            command.payload(),
                            command.forward()
                    )
            ));
            return new ControlCommandConsumeResponse(
                    Collections.unmodifiableMap(response)
            );
        }
    }

    public record ControlResultBatchRequest(
            @NotEmpty @Size(max = 100) List<@NotBlank String> results
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Control Result batch field: " + name
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
