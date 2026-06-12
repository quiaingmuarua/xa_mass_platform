package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TransportDeliveryCommandBatchCodec {

    private final Gson gson;

    TransportDeliveryCommandBatchCodec() {
        this(new GsonBuilder().create());
    }

    TransportDeliveryCommandBatchCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(DeliveryCommandBatch batch) {
        Objects.requireNonNull(batch, "batch");
        List<DeliveryCommandRecord> commands = batch.commands().stream()
                .map(this::toRecord)
                .toList();
        return gson.toJson(new DeliveryCommandBatchRecord(
                batch.deliveryQueueKey(),
                batch.targetTransportNodeId(),
                commands
        ));
    }

    DeliveryCommandBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDeliveryCommandBatchRecord record = gson.fromJson(json, DecodedDeliveryCommandBatchRecord.class);
        if (record == null || record.deliveryQueueKey == null || record.targetTransportNodeId == null
                || record.commands == null || record.commands.isEmpty()) {
            throw new IllegalArgumentException("encoded delivery command batch is incomplete");
        }
        List<DeliveryCommand> commands = record.commands.stream()
                .map(this::fromRecord)
                .toList();
        return new DeliveryCommandBatch(record.deliveryQueueKey, record.targetTransportNodeId, commands);
    }

    private DeliveryCommandRecord toRecord(DeliveryCommand command) {
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

    private DeliveryCommand fromRecord(DecodedDeliveryCommandRecord record) {
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

    private record DeliveryCommandBatchRecord(String deliveryQueueKey,
                                              String targetTransportNodeId,
                                              List<DeliveryCommandRecord> commands) {
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

    private static final class DecodedDeliveryCommandBatchRecord {
        private String deliveryQueueKey;
        private String targetTransportNodeId;
        private List<DecodedDeliveryCommandRecord> commands;
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
