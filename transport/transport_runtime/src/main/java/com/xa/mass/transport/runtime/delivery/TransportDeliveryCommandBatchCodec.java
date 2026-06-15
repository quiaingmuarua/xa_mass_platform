package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;

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
        List<DeliveryCommandRecord> items = batch.items().stream()
                .map(this::toCommandRecord)
                .toList();
        return gson.toJson(new DeliveryCommandBatchRecord(
                batch.deliveryBucketId(),
                batch.deliveryLaneKey(),
                batch.targetTransportNodeId(),
                items
        ));
    }

    DeliveryCommandBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDeliveryCommandBatchRecord record = gson.fromJson(json, DecodedDeliveryCommandBatchRecord.class);
        if (record == null || record.deliveryBucketId == null || record.deliveryLaneKey == null
                || record.targetTransportNodeId == null
                || record.items == null || record.items.isEmpty()) {
            throw new IllegalArgumentException("encoded delivery command batch is incomplete");
        }
        List<DeliveryCommand> items = record.items.stream()
                .map(item -> fromCommandRecord(record.deliveryBucketId, item))
                .toList();
        return new DeliveryCommandBatch(record.deliveryBucketId, record.deliveryLaneKey, record.targetTransportNodeId, items);
    }

    private DeliveryCommandRecord toCommandRecord(DeliveryCommand command) {
        return new DeliveryCommandRecord(
                command.getCommandId(),
                command.getSelectedWorkerId(),
                toContentRecord(command.getContent()),
                toExecutionContextRecord(command.getExecutionContext()),
                command.getDeadlineEpochMillis(),
                command.getCreatedAtEpochMillis()
        );
    }

    private TaskDispatchContentRecord toContentRecord(TaskDispatchContent content) {
        return new TaskDispatchContentRecord(
                content.taskId(),
                content.messageId(),
                content.eventCode(),
                content.input(),
                content.sharedConfig()
        );
    }

    private TaskDispatchExecutionContextRecord toExecutionContextRecord(TaskDispatchExecutionContext context) {
        return new TaskDispatchExecutionContextRecord(
                context.attemptId(),
                context.attemptNo(),
                context.retryCount(),
                context.batchId()
        );
    }

    private DeliveryCommand fromCommandRecord(String deliveryBucketId, DecodedDeliveryCommandRecord record) {
        if (record == null || record.content == null || record.executionContext == null) {
            throw new IllegalArgumentException("encoded delivery command is incomplete");
        }
        return new DeliveryCommand(
                record.commandId,
                deliveryBucketId,
                record.selectedWorkerId,
                fromContentRecord(record.content),
                fromExecutionContextRecord(record.executionContext),
                record.deadlineEpochMillis,
                record.createdAtEpochMillis
        );
    }

    private TaskDispatchContent fromContentRecord(DecodedTaskDispatchContentRecord record) {
        return new TaskDispatchContent(
                record.taskId,
                record.messageId,
                record.eventCode,
                record.input,
                record.sharedConfig
        );
    }

    private TaskDispatchExecutionContext fromExecutionContextRecord(DecodedTaskDispatchExecutionContextRecord record) {
        return new TaskDispatchExecutionContext(
                record.attemptId,
                record.attemptNo,
                record.retryCount,
                record.batchId
        );
    }

    private record DeliveryCommandBatchRecord(String deliveryBucketId,
                                              String deliveryLaneKey,
                                              String targetTransportNodeId,
                                              List<DeliveryCommandRecord> items) {
    }

    private record DeliveryCommandRecord(String commandId,
                                         String selectedWorkerId,
                                         TaskDispatchContentRecord content,
                                         TaskDispatchExecutionContextRecord executionContext,
                                         long deadlineEpochMillis,
                                         long createdAtEpochMillis) {
    }

    private record TaskDispatchContentRecord(String taskId,
                                             String messageId,
                                             String eventCode,
                                             Map<String, Object> input,
                                             Map<String, Object> sharedConfig) {
    }

    private record TaskDispatchExecutionContextRecord(String attemptId,
                                                      int attemptNo,
                                                      int retryCount,
                                                      String batchId) {
    }

    private static final class DecodedDeliveryCommandBatchRecord {
        private String deliveryBucketId;
        private String deliveryLaneKey;
        private String targetTransportNodeId;
        private List<DecodedDeliveryCommandRecord> items;
    }

    private static final class DecodedDeliveryCommandRecord {
        private String commandId;
        private String selectedWorkerId;
        private DecodedTaskDispatchContentRecord content;
        private DecodedTaskDispatchExecutionContextRecord executionContext;
        private long deadlineEpochMillis;
        private long createdAtEpochMillis;
    }

    private static final class DecodedTaskDispatchContentRecord {
        private String taskId;
        private String messageId;
        private String eventCode;
        private Map<String, Object> input;
        private Map<String, Object> sharedConfig;
    }

    private static final class DecodedTaskDispatchExecutionContextRecord {
        private String attemptId;
        private int attemptNo;
        private int retryCount;
        private String batchId;
    }

}
