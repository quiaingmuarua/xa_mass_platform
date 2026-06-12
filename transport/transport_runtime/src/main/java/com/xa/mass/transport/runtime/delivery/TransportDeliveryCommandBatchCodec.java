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
        List<ResolvedDeliveryItemRecord> items = batch.items().stream()
                .map(this::toRecord)
                .toList();
        return gson.toJson(new DeliveryCommandBatchRecord(
                batch.adapterId(),
                batch.deliveryQueueKey(),
                batch.targetTransportNodeId(),
                items
        ));
    }

    DeliveryCommandBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDeliveryCommandBatchRecord record = gson.fromJson(json, DecodedDeliveryCommandBatchRecord.class);
        if (record == null || record.adapterId == null || record.deliveryQueueKey == null || record.targetTransportNodeId == null
                || record.items == null || record.items.isEmpty()) {
            throw new IllegalArgumentException("encoded delivery command batch is incomplete");
        }
        List<ResolvedDeliveryItem> items = record.items.stream()
                .map(this::fromRecord)
                .toList();
        return new DeliveryCommandBatch(record.adapterId, record.deliveryQueueKey, record.targetTransportNodeId, items);
    }

    private ResolvedDeliveryItemRecord toRecord(ResolvedDeliveryItem item) {
        return new ResolvedDeliveryItemRecord(
                toCommandRecord(item.command()),
                toEndpointRecord(item.endpoint())
        );
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
                context.batchId(),
                context.taskName(),
                context.project(),
                context.userId()
        );
    }

    private EndpointLeaseRecord toEndpointRecord(EndpointLease endpoint) {
        return new EndpointLeaseRecord(
                endpoint.selectedWorkerId(),
                endpoint.routeKey(),
                endpoint.transportNodeId(),
                endpoint.connectionId(),
                endpoint.leaseExpireAtEpochMillis()
        );
    }

    private ResolvedDeliveryItem fromRecord(DecodedResolvedDeliveryItemRecord record) {
        if (record == null || record.command == null || record.endpoint == null) {
            throw new IllegalArgumentException("encoded delivery item is incomplete");
        }
        return new ResolvedDeliveryItem(
                fromCommandRecord(record.command),
                fromEndpointRecord(record.endpoint)
        );
    }

    private DeliveryCommand fromCommandRecord(DecodedDeliveryCommandRecord record) {
        if (record == null || record.content == null || record.executionContext == null) {
            throw new IllegalArgumentException("encoded delivery command is incomplete");
        }
        return new DeliveryCommand(
                record.commandId,
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
                record.batchId,
                record.taskName,
                record.project,
                record.userId
        );
    }

    private EndpointLease fromEndpointRecord(DecodedEndpointLeaseRecord record) {
        return new EndpointLease(
                record.selectedWorkerId,
                record.routeKey,
                record.transportNodeId,
                record.connectionId,
                record.leaseExpireAtEpochMillis
        );
    }

    private record DeliveryCommandBatchRecord(String adapterId,
                                              String deliveryQueueKey,
                                              String targetTransportNodeId,
                                              List<ResolvedDeliveryItemRecord> items) {
    }

    private record ResolvedDeliveryItemRecord(DeliveryCommandRecord command,
                                              EndpointLeaseRecord endpoint) {
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
                                                      String batchId,
                                                      String taskName,
                                                      String project,
                                                      String userId) {
    }

    private record EndpointLeaseRecord(String selectedWorkerId,
                                       String routeKey,
                                       String transportNodeId,
                                       String connectionId,
                                       long leaseExpireAtEpochMillis) {
    }

    private static final class DecodedDeliveryCommandBatchRecord {
        private String adapterId;
        private String deliveryQueueKey;
        private String targetTransportNodeId;
        private List<DecodedResolvedDeliveryItemRecord> items;
    }

    private static final class DecodedResolvedDeliveryItemRecord {
        private DecodedDeliveryCommandRecord command;
        private DecodedEndpointLeaseRecord endpoint;
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
        private String taskName;
        private String project;
        private String userId;
    }

    private static final class DecodedEndpointLeaseRecord {
        private String selectedWorkerId;
        private String routeKey;
        private String transportNodeId;
        private String connectionId;
        private long leaseExpireAtEpochMillis;
    }
}
