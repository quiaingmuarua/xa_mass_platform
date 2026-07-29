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
            @Positive int limit
    ) {
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
            Map<String, WorkerCommandResponse> workerCommandsByWorkerId
    ) {
        static WorkerCommandConsumeResponse from(
                Map<String, WorkerCommandEnvelope> commands
        ) {
            Map<String, WorkerCommandResponse> response =
                    new LinkedHashMap<>();
            commands.forEach((workerId, command) -> response.put(
                    workerId,
                    WorkerCommandResponse.from(command)
            ));
            return new WorkerCommandConsumeResponse(
                    Collections.unmodifiableMap(response)
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
}
