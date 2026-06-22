package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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

    String encode(AdapterMailboxDispatchBatch batch) {
        Objects.requireNonNull(batch, "batch");
        List<DispatchMessageRecord> items = batch.items().stream()
                .map(DispatchMessageRecord::from)
                .toList();
        return gson.toJson(new AdapterMailboxDispatchBatchRecord(
                batch.adapterMailboxKey(),
                items
        ));
    }

    String encodeItem(DispatchMessage item) {
        Objects.requireNonNull(item, "item");
        return gson.toJson(DispatchMessageRecord.from(item));
    }

    AdapterMailboxDispatchBatch decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedAdapterMailboxDispatchBatchRecord record = gson.fromJson(json, DecodedAdapterMailboxDispatchBatchRecord.class);
        if (record == null || record.adapterMailboxKey == null || record.items == null || record.items.isEmpty()) {
            throw new IllegalArgumentException("encoded dispatch batch is incomplete");
        }
        List<DispatchMessage> items = record.items.stream()
                .map(TransportDispatchBatchCodec::fromItemRecord)
                .toList();
        return new AdapterMailboxDispatchBatch(
                record.adapterMailboxKey,
                items
        );
    }

    DispatchMessage decodeItem(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        DecodedDispatchMessageRecord record = gson.fromJson(json, DecodedDispatchMessageRecord.class);
        return fromItemRecord(record);
    }

    private static DispatchMessage fromItemRecord(DecodedDispatchMessageRecord record) {
        if (record == null || record.payload == null || record.correlationRef == null) {
            throw new IllegalArgumentException("encoded dispatch message is incomplete");
        }
        return new DispatchMessage(
                record.deliveryId,
                record.selectedWorkerId,
                record.payload,
                record.correlationRef,
                record.deadlineEpochMillis,
                record.createdAtEpochMillis
        );
    }

    private record AdapterMailboxDispatchBatchRecord(String adapterMailboxKey,
                                                     List<DispatchMessageRecord> items) {
    }

    private record DispatchMessageRecord(String deliveryId,
                                             String selectedWorkerId,
                                             String payload,
                                             String correlationRef,
                                             long deadlineEpochMillis,
                                             long createdAtEpochMillis) {
        private static DispatchMessageRecord from(DispatchMessage item) {
            return new DispatchMessageRecord(
                    item.deliveryId(),
                    item.selectedWorkerId(),
                    item.payload(),
                    item.correlationRef(),
                    item.deadlineEpochMillis(),
                    item.createdAtEpochMillis()
            );
        }
    }

    private static final class DecodedAdapterMailboxDispatchBatchRecord {
        private String adapterMailboxKey;
        private List<DecodedDispatchMessageRecord> items;
    }

    private static final class DecodedDispatchMessageRecord {
        private String deliveryId;
        private String selectedWorkerId;
        private String payload;
        private String correlationRef;
        private long deadlineEpochMillis;
        private long createdAtEpochMillis;
    }
}
