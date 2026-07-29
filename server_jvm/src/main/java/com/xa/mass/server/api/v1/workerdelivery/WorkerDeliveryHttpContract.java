package com.xa.mass.server.api.v1.workerdelivery;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerDeliveryHttpContract {

    private WorkerDeliveryHttpContract() {
    }

    public record WorkerCommandConsumeRequest(
            String cursor,
            @Positive int scanCount
    ) {
        public WorkerCommandConsumeRequest {
            if (cursor != null && !isDecimal(cursor)) {
                throw new IllegalArgumentException(
                        "cursor must be a non-negative Redis cursor"
                );
            }
        }

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw WorkerDeliveryException.invalid(
                    "Unknown Worker command consume field: " + name
            );
        }
    }

    public record WorkerCommandResponse(
            String commandId,
            String messageType,
            long executeBeforeMillis,
            String opaqueItem
    ) {
        static WorkerCommandResponse from(WorkerCommandEnvelope command) {
            return new WorkerCommandResponse(
                    command.commandId(),
                    command.messageType().name(),
                    command.executeBeforeMillis(),
                    command.opaqueItem()
            );
        }
    }

    public record WorkerCommandConsumeResponse(
            Map<String, WorkerCommandResponse> workerCommandsByWorkerId,
            String nextCursor
    ) {
        static WorkerCommandConsumeResponse from(
                Map<String, WorkerCommandEnvelope> commands,
                String nextCursor
        ) {
            Map<String, WorkerCommandResponse> response =
                    new LinkedHashMap<>();
            commands.forEach((workerId, command) -> response.put(
                    workerId,
                    WorkerCommandResponse.from(command)
            ));
            return new WorkerCommandConsumeResponse(
                    Collections.unmodifiableMap(response),
                    nextCursor
            );
        }
    }

    public record SeedResultRequest(
            @NotBlank String commandId,
            @NotBlank String opaqueResultContext,
            @NotBlank String outcomeCode,
            String opaqueResultPayload
    ) {
        SeedResult toSeedResult() {
            try {
                return new SeedResult(
                        commandId,
                        opaqueResultContext,
                        outcomeCode,
                        opaqueResultPayload
                );
            } catch (IllegalArgumentException error) {
                throw WorkerDeliveryException.invalid(error.getMessage());
            }
        }

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw WorkerDeliveryException.invalid(
                    "Unknown SeedResult field: " + name
            );
        }
    }

    public record SeedResultBatchRequest(
            @NotEmpty List<@Valid SeedResultRequest> results
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw WorkerDeliveryException.invalid(
                    "Unknown SeedResult batch field: " + name
            );
        }
    }

    public record AcceptedResponse(boolean accepted) {
    }

    public record AcceptedCountResponse(int acceptedCount) {
    }

    private static boolean isDecimal(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
