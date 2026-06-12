package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.Map;
import java.util.Objects;

final class TransportDeliveryFailureEventCodec {

    private final Gson gson;

    TransportDeliveryFailureEventCodec() {
        this(new GsonBuilder().create());
    }

    TransportDeliveryFailureEventCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(TransportDeliveryFailureEvent event) {
        Objects.requireNonNull(event, "event");
        return gson.toJson(new TransportDeliveryFailureEventRecord(
                toRecord(event.command()),
                toRecord(event.outcome()),
                event.detail()
        ));
    }

    TransportDeliveryFailureEvent decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedTransportDeliveryFailureEventRecord record =
                gson.fromJson(json, DecodedTransportDeliveryFailureEventRecord.class);
        if (record == null || record.command == null || record.outcome == null) {
            throw new IllegalArgumentException("encoded delivery failure event is incomplete");
        }
        return new TransportDeliveryFailureEvent(
                fromRecord(record.command),
                fromRecord(record.outcome),
                record.detail
        );
    }

    private static DeliveryCommandRecord toRecord(DeliveryCommand command) {
        return new DeliveryCommandRecord(
                command.getCommandId(),
                command.getAdapterId(),
                command.getSelectedWorkerId(),
                command.getDeliveryQueueKey(),
                command.getTargetTransportNodeId(),
                command.getRouteKey(),
                command.getConnectionToken(),
                command.getPayload(),
                command.getCorrelation(),
                command.getDeadlineEpochMillis(),
                command.getCreatedAtEpochMillis()
        );
    }

    private static DispatchOutcomeRecord toRecord(DispatchOutcome outcome) {
        return new DispatchOutcomeRecord(
                outcome.getDeliveryId(),
                outcome.getAdapterId(),
                outcome.getSelectedWorkerId(),
                outcome.getDeliveryQueueKey(),
                outcome.getRouteKey(),
                outcome.getAttemptId(),
                outcome.getStatus(),
                outcome.isRetryable(),
                outcome.getReason(),
                outcome.getTransportNodeId(),
                outcome.getConnectionId(),
                outcome.getOccurredAtEpochMillis()
        );
    }

    private static DeliveryCommand fromRecord(DecodedDeliveryCommandRecord record) {
        if (record == null || record.payload == null) {
            throw new IllegalArgumentException("encoded delivery command is incomplete");
        }
        TransportPacket packet = TransportPacket.fromDecodedJson(
                record.payload.version,
                record.payload.packetId,
                record.payload.traceId,
                record.payload.type,
                record.payload.adapterId,
                record.payload.routeKey,
                record.payload.taskId,
                record.payload.messageId,
                record.payload.attemptId,
                record.payload.eventCode,
                record.payload.contentType,
                record.payload.payload
        );
        return new DeliveryCommand(
                record.commandId,
                record.adapterId,
                record.selectedWorkerId,
                record.deliveryQueueKey,
                record.targetTransportNodeId,
                record.routeKey,
                record.connectionToken,
                packet,
                record.correlation,
                record.deadlineEpochMillis,
                record.createdAtEpochMillis
        );
    }

    private static DispatchOutcome fromRecord(DecodedDispatchOutcomeRecord record) {
        return new DispatchOutcome(
                record.deliveryId,
                record.adapterId,
                record.selectedWorkerId,
                record.deliveryQueueKey,
                record.routeKey,
                record.attemptId,
                record.status,
                record.retryable,
                record.reason,
                record.transportNodeId,
                record.connectionId,
                record.occurredAtEpochMillis
        );
    }

    private record TransportDeliveryFailureEventRecord(DeliveryCommandRecord command,
                                                       DispatchOutcomeRecord outcome,
                                                       String detail) {
    }

    private record DeliveryCommandRecord(String commandId,
                                         String adapterId,
                                         String selectedWorkerId,
                                         String deliveryQueueKey,
                                         String targetTransportNodeId,
                                         String routeKey,
                                         String connectionToken,
                                         TransportPacket payload,
                                         Map<String, String> correlation,
                                         long deadlineEpochMillis,
                                         long createdAtEpochMillis) {
    }

    private record DispatchOutcomeRecord(String deliveryId,
                                         String adapterId,
                                         String selectedWorkerId,
                                         String deliveryQueueKey,
                                         String routeKey,
                                         String attemptId,
                                         DispatchOutcomeStatus status,
                                         boolean retryable,
                                         String reason,
                                         String transportNodeId,
                                         String connectionId,
                                         long occurredAtEpochMillis) {
    }

    private static final class DecodedTransportDeliveryFailureEventRecord {
        private DecodedDeliveryCommandRecord command;
        private DecodedDispatchOutcomeRecord outcome;
        private String detail;
    }

    private static final class DecodedDeliveryCommandRecord {
        private String commandId;
        private String adapterId;
        private String selectedWorkerId;
        private String deliveryQueueKey;
        private String targetTransportNodeId;
        private String routeKey;
        private String connectionToken;
        private DecodedTransportPacketRecord payload;
        private Map<String, String> correlation;
        private long deadlineEpochMillis;
        private long createdAtEpochMillis;
    }

    private static final class DecodedDispatchOutcomeRecord {
        private String deliveryId;
        private String adapterId;
        private String selectedWorkerId;
        private String deliveryQueueKey;
        private String routeKey;
        private String attemptId;
        private DispatchOutcomeStatus status;
        private boolean retryable;
        private String reason;
        private String transportNodeId;
        private String connectionId;
        private long occurredAtEpochMillis;
    }

    private static final class DecodedTransportPacketRecord {
        private int version;
        private String packetId;
        private String traceId;
        private PacketType type;
        private String adapterId;
        private String routeKey;
        private String taskId;
        private String messageId;
        private String attemptId;
        private String eventCode;
        private String contentType;
        private Map<String, Object> payload;
    }
}
