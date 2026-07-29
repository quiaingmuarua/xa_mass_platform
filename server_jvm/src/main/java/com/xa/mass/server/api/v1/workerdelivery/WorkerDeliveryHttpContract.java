package com.xa.mass.server.api.v1.workerdelivery;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
            throw invalid(
                    "Unknown Worker command consume field: " + name
            );
        }
    }

    public record WorkerCommandResponse(
            String messageId,
            String src,
            String dst,
            String messageType,
            long executeBeforeMillis,
            String payload,
            String forward
    ) {
        static WorkerCommandResponse from(WorkerCommand command) {
            return new WorkerCommandResponse(
                    command.messageId(),
                    command.src().wireValue(),
                    command.dst().wireValue(),
                    command.messageType(),
                    command.executeBeforeMillis(),
                    command.payload(),
                    command.forward()
            );
        }
    }

    public record WorkerCommandConsumeResponse(
            Map<String, WorkerCommandResponse> workerCommandsByWorkerId
    ) {
        static WorkerCommandConsumeResponse from(
                Map<String, WorkerCommand> commands
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

    public record WorkerResultRequest(
            @NotBlank String messageId,
            @NotBlank String dst,
            @NotBlank String messageType,
            @NotBlank String outcomeCode,
            @NotNull String payload,
            @NotNull String forward
    ) {
        WorkerResult toWorkerResult() {
            try {
                return new WorkerResult(
                        messageId,
                        WorkerMessageEndpoint.fromWire(dst),
                        messageType,
                        outcomeCode,
                        payload,
                        forward
                );
            } catch (IllegalArgumentException error) {
                throw invalid(error.getMessage());
            }
        }

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw invalid(
                    "Unknown WorkerResult field: " + name
            );
        }
    }

    public record WorkerResultBatchRequest(
            @NotEmpty List<@NotBlank String> results
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw invalid(
                    "Unknown WorkerResult batch field: " + name
            );
        }
    }

    public record AcceptedResponse(boolean accepted) {
    }

    public record WorkerResultBatchResponse(
            int acceptedCount,
            int rejectedCount
    ) {
    }

    private static ServerException invalid(String message) {
        return new ServerException(
                ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST,
                "workerDelivery.decodeHttpRequest",
                message,
                null
        );
    }
}
