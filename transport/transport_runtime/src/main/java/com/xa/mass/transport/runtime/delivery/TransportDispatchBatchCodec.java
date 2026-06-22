package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.transport.routing.RoutingTarget;

import java.util.List;
import java.util.Objects;

final class TransportDispatchBatchCodec {

    private final Gson gson;

    TransportDispatchBatchCodec() {
        this(new GsonBuilder().create());
    }

    TransportDispatchBatchCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(DispatchRoutingBatch batch) {
        Objects.requireNonNull(batch, "batch");
        List<DispatchRoutingItemRecord> items = batch.items().stream()
                .map(DispatchRoutingItemRecord::from)
                .toList();
        return gson.toJson(new DispatchRoutingBatchRecord(
                RoutingTargetRecord.from(batch.target()),
                items
        ));
    }

    String encodeItem(DispatchRoutingItem item) {
        Objects.requireNonNull(item, "item");
        return gson.toJson(DispatchRoutingItemRecord.from(item));
    }

    DispatchRoutingBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDispatchRoutingBatchRecord record = gson.fromJson(json, DecodedDispatchRoutingBatchRecord.class);
        if (record == null || record.target == null || record.items == null || record.items.isEmpty()) {
            throw new IllegalArgumentException("encoded dispatch routing batch is incomplete");
        }
        List<DispatchRoutingItem> items = record.items.stream()
                .map(TransportDispatchBatchCodec::fromItemRecord)
                .toList();
        return new DispatchRoutingBatch(
                new RoutingTarget(record.target.ownerKind, record.target.ownerRef),
                items
        );
    }

    DispatchRoutingItem decodeItem(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDispatchRoutingItemRecord record = gson.fromJson(json, DecodedDispatchRoutingItemRecord.class);
        return fromItemRecord(record);
    }

    private static DispatchRoutingItem fromItemRecord(DecodedDispatchRoutingItemRecord record) {
        if (record == null || record.payload == null || record.correlationRef == null) {
            throw new IllegalArgumentException("encoded dispatch routing item is incomplete");
        }
        return new DispatchRoutingItem(
                record.deliveryId,
                record.selectedWorkerId,
                record.payload,
                record.correlationRef,
                record.deadlineEpochMillis,
                record.createdAtEpochMillis
        );
    }

    private record DispatchRoutingBatchRecord(RoutingTargetRecord target,
                                              List<DispatchRoutingItemRecord> items) {
    }

    private record RoutingTargetRecord(String ownerKind,
                                       String ownerRef) {
        private static RoutingTargetRecord from(RoutingTarget target) {
            return new RoutingTargetRecord(target.ownerKind(), target.ownerRef());
        }
    }

    private record DispatchRoutingItemRecord(String deliveryId,
                                             String selectedWorkerId,
                                             String payload,
                                             String correlationRef,
                                             long deadlineEpochMillis,
                                             long createdAtEpochMillis) {
        private static DispatchRoutingItemRecord from(DispatchRoutingItem item) {
            return new DispatchRoutingItemRecord(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.payload(),
                    item.correlationRef(),
                    item.deadlineEpochMillis(),
                    item.createdAtEpochMillis()
            );
        }
    }

    private static final class DecodedDispatchRoutingBatchRecord {
        private DecodedRoutingTargetRecord target;
        private List<DecodedDispatchRoutingItemRecord> items;
    }

    private static final class DecodedRoutingTargetRecord {
        private String ownerKind;
        private String ownerRef;
    }

    private static final class DecodedDispatchRoutingItemRecord {
        private String deliveryId;
        private String selectedWorkerId;
        private String payload;
        private String correlationRef;
        private long deadlineEpochMillis;
        private long createdAtEpochMillis;
    }
}
