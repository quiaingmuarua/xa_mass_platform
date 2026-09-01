package com.xa.mass.server.api.v1.contract.delivery;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkerDeliveryHttpContract {

    private WorkerDeliveryHttpContract() {
    }

    public record WorkerCommandResponse(
            String src,
            String dst,
            String messageType,
            long executeBeforeMillis,
            String payload,
            String forward
    ) {
        public static WorkerCommandResponse from(DeliveryCommand command) {
            return new WorkerCommandResponse(
                    command.src().wireValue(),
                    command.dst().wireValue(),
                    command.messageType(),
                    command.executeBeforeMillis(),
                    command.payload(),
                    command.forward()
            );
        }

        public static Map<String, WorkerCommandResponse> fromCommands(
                Map<String, DeliveryCommand> commands
        ) {
            Map<String, WorkerCommandResponse> response =
                    new LinkedHashMap<>();
            commands.forEach((entryKey, command) -> response.put(
                    entryKey,
                    WorkerCommandResponse.from(command)
            ));
            return Collections.unmodifiableMap(response);
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
        public DeliveryReport toDeliveryReport() {
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
