package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

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
                toRecord(event.groupContext()),
                toRecord(event.itemSnapshot()),
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
        if (record == null || record.groupContext == null || record.itemSnapshot == null
                || record.outcome == null) {
            throw new IllegalArgumentException("encoded delivery failure event is incomplete");
        }
        return new TransportDeliveryFailureEvent(
                fromRecord(record.groupContext),
                fromRecord(record.itemSnapshot),
                fromRecord(record.outcome),
                record.detail
        );
    }

    private static DeliveryObservationGroupContextRecord toRecord(DeliveryObservationGroupContext groupContext) {
        return new DeliveryObservationGroupContextRecord(
                groupContext.adapterId(),
                groupContext.deliveryQueueKey(),
                groupContext.targetTransportNodeId(),
                groupContext.occurredAtEpochMillis()
        );
    }

    private static DeliveryObservationItemSnapshotRecord toRecord(DeliveryObservationItemSnapshot itemSnapshot) {
        return new DeliveryObservationItemSnapshotRecord(
                itemSnapshot.commandId(),
                itemSnapshot.selectedWorkerId(),
                itemSnapshot.taskId(),
                itemSnapshot.messageId(),
                itemSnapshot.attemptId(),
                itemSnapshot.attemptNo(),
                itemSnapshot.routeKey(),
                itemSnapshot.connectionId()
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

    private static DeliveryObservationGroupContext fromRecord(DecodedDeliveryObservationGroupContextRecord record) {
        return new DeliveryObservationGroupContext(
                record.adapterId,
                record.deliveryQueueKey,
                record.targetTransportNodeId,
                record.occurredAtEpochMillis
        );
    }

    private static DeliveryObservationItemSnapshot fromRecord(DecodedDeliveryObservationItemSnapshotRecord record) {
        return new DeliveryObservationItemSnapshot(
                record.commandId,
                record.selectedWorkerId,
                record.taskId,
                record.messageId,
                record.attemptId,
                record.attemptNo,
                record.routeKey,
                record.connectionId
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

    private record TransportDeliveryFailureEventRecord(DeliveryObservationGroupContextRecord groupContext,
                                                       DeliveryObservationItemSnapshotRecord itemSnapshot,
                                                       DispatchOutcomeRecord outcome,
                                                       String detail) {
    }

    private record DeliveryObservationGroupContextRecord(String adapterId,
                                                         String deliveryQueueKey,
                                                         String targetTransportNodeId,
                                                         long occurredAtEpochMillis) {
    }

    private record DeliveryObservationItemSnapshotRecord(String commandId,
                                                         String selectedWorkerId,
                                                         String taskId,
                                                         String messageId,
                                                         String attemptId,
                                                         int attemptNo,
                                                         String routeKey,
                                                         String connectionId) {
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
        private DecodedDeliveryObservationGroupContextRecord groupContext;
        private DecodedDeliveryObservationItemSnapshotRecord itemSnapshot;
        private DecodedDispatchOutcomeRecord outcome;
        private String detail;
    }

    private static final class DecodedDeliveryObservationGroupContextRecord {
        private String adapterId;
        private String deliveryQueueKey;
        private String targetTransportNodeId;
        private long occurredAtEpochMillis;
    }

    private static final class DecodedDeliveryObservationItemSnapshotRecord {
        private String commandId;
        private String selectedWorkerId;
        private String taskId;
        private String messageId;
        private String attemptId;
        private int attemptNo;
        private String routeKey;
        private String connectionId;
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
}
