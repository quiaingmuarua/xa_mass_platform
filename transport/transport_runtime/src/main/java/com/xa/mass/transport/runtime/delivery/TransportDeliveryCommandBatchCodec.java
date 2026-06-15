package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.DeliveryCommand;

import java.util.List;
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
        List<DeliveryCommandRecord> items = batch.items().stream()
                .map(this::toCommandRecord)
                .toList();
        return gson.toJson(new DeliveryCommandBatchRecord(
                batch.deliveryQueueKey(),
                items
        ));
    }

    DeliveryCommandBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDeliveryCommandBatchRecord record = gson.fromJson(json, DecodedDeliveryCommandBatchRecord.class);
        if (record == null || record.deliveryQueueKey == null || record.items == null || record.items.isEmpty()) {
            throw new IllegalArgumentException("encoded delivery command batch is incomplete");
        }
        List<DeliveryCommand> items = record.items.stream()
                .map(this::fromCommandRecord)
                .toList();
        return new DeliveryCommandBatch(record.deliveryQueueKey, items);
    }

    private DeliveryCommandRecord toCommandRecord(DeliveryCommand command) {
        return new DeliveryCommandRecord(
                command.getCommandId(),
                command.getDeliveryBucketId(),
                command.getSelectedWorkerId(),
                command.getPayload(),
                command.getCorrelationRef(),
                command.getDeadlineEpochMillis(),
                command.getCreatedAtEpochMillis()
        );
    }

    private DeliveryCommand fromCommandRecord(DecodedDeliveryCommandRecord record) {
        if (record == null || record.payload == null || record.correlationRef == null) {
            throw new IllegalArgumentException("encoded delivery command is incomplete");
        }
        return new DeliveryCommand(
                record.commandId,
                record.deliveryBucketId,
                record.selectedWorkerId,
                record.payload,
                record.correlationRef,
                record.deadlineEpochMillis,
                record.createdAtEpochMillis
        );
    }

    private record DeliveryCommandBatchRecord(String deliveryQueueKey,
                                              List<DeliveryCommandRecord> items) {
    }

    private record DeliveryCommandRecord(String commandId,
                                         String deliveryBucketId,
                                         String selectedWorkerId,
                                         String payload,
                                         String correlationRef,
                                         long deadlineEpochMillis,
                                         long createdAtEpochMillis) {
    }

    private static final class DecodedDeliveryCommandBatchRecord {
        private String deliveryQueueKey;
        private List<DecodedDeliveryCommandRecord> items;
    }

    private static final class DecodedDeliveryCommandRecord {
        private String commandId;
        private String deliveryBucketId;
        private String selectedWorkerId;
        private String payload;
        private String correlationRef;
        private long deadlineEpochMillis;
        private long createdAtEpochMillis;
    }

}
