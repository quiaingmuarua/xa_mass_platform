package com.xa.mass.server.api.v1.workerdelivery;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
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
            String src,
            String dst,
            String messageType,
            long executeBeforeMillis,
            String payload,
            String forward
    ) {
        static WorkerCommandResponse from(DeliveryCommand command) {
            return new WorkerCommandResponse(
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
            Map<String, WorkerCommandResponse> commands
    ) {
        public static WorkerCommandConsumeResponse from(
                Map<String, DeliveryCommand> commands
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
            @NotBlank String src,
            @NotBlank String sourceId,
            @NotBlank String dst,
            @NotBlank String messageType,
            @NotBlank String outcomeCode,
            @NotNull String payload,
            @NotNull String forward
    ) {
        DeliveryReport toDeliveryReport() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("src", src);
            fields.put("sourceId", sourceId);
            fields.put("dst", dst);
            fields.put("messageType", messageType);
            fields.put("outcomeCode", outcomeCode);
            fields.put("payload", payload);
            fields.put("forward", forward);
            DeliveryReport report = new WorkerDeliveryCodec()
                    .decodeDeliveryReport(fields);
            if (report == null) {
                throw invalid("DeliveryReport is invalid");
            }
            return report;
        }

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw invalid(
                    "Unknown DeliveryReport field: " + name
            );
        }
    }

    public record WorkerResultBatchRequest(
            @NotEmpty List<@NotBlank String> results
    ) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw invalid(
                    "Unknown DeliveryReport batch field: " + name
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
