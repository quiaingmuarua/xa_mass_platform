package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

import java.util.List;
import java.util.Objects;

final class TransportDeliveryFailureEventCodec {

    private final Gson gson;
    private final TransportDeliveryCommandBatchCodec commandCodec;

    TransportDeliveryFailureEventCodec() {
        this(new GsonBuilder().create(), new TransportDeliveryCommandBatchCodec());
    }

    TransportDeliveryFailureEventCodec(Gson gson, TransportDeliveryCommandBatchCodec commandCodec) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.commandCodec = Objects.requireNonNull(commandCodec, "commandCodec");
    }

    String encode(TransportDeliveryFailureEvent event) {
        Objects.requireNonNull(event, "event");
        String commandBatchJson = commandCodec.encode(new DeliveryCommandBatch(
                event.command().getDeliveryQueueKey(),
                event.command().getTargetTransportNodeId(),
                List.of(event.command())
        ));
        return gson.toJson(new TransportDeliveryFailureEventRecord(
                commandBatchJson,
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
        if (record == null || record.commandBatchJson == null || record.outcome == null) {
            throw new IllegalArgumentException("encoded delivery failure event is incomplete");
        }
        DeliveryCommandBatch batch = commandCodec.decode(record.commandBatchJson);
        return new TransportDeliveryFailureEvent(
                batch.commands().getFirst(),
                fromRecord(record.outcome),
                record.detail
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

    private record TransportDeliveryFailureEventRecord(String commandBatchJson,
                                                       DispatchOutcomeRecord outcome,
                                                       String detail) {
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
        private String commandBatchJson;
        private DecodedDispatchOutcomeRecord outcome;
        private String detail;
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
