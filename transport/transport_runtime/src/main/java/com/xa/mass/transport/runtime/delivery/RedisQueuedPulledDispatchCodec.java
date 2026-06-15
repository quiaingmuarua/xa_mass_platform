package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.runtime.queue.KeyedQueueEntry;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

final class RedisQueuedPulledDispatchCodec {

    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder KEY_DECODER = Base64.getUrlDecoder();
    private static final String WORKER_INDEX_SEPARATOR = ":worker-index:";

    private final Gson gson;

    RedisQueuedPulledDispatchCodec() {
        this(new GsonBuilder().create());
    }

    RedisQueuedPulledDispatchCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encodeKeyPart(DeliveryQueueKey key) {
        Objects.requireNonNull(key, "key");
        return encodeKeyToken(key.deliveryQueueKey())
                + WORKER_INDEX_SEPARATOR
                + encodeKeyToken(key.selectedWorkerId());
    }

    DeliveryQueueKey decodeKeyPart(String encodedKeyPart) {
        if (encodedKeyPart == null || encodedKeyPart.isBlank()) {
            throw new IllegalArgumentException("encodedKeyPart must not be blank");
        }
        String[] parts = encodedKeyPart.split(WORKER_INDEX_SEPARATOR, 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("encodedKeyPart must include selected worker index");
        }
        return new DeliveryQueueKey(decodeKeyToken(parts[0]), decodeKeyToken(parts[1]));
    }

    byte[] encodeEntry(KeyedQueueEntry<QueuedPulledDispatch> entry) {
        Objects.requireNonNull(entry, "entry");
        QueuedPulledDispatch item = Objects.requireNonNull(entry.value(), "entry.value");
        RedisQueuedPulledDispatchRecord record = new RedisQueuedPulledDispatchRecord(
                item.deliveryId(),
                item.selectedWorkerId(),
                item.payload(),
                item.correlationRef(),
                item.createdAtEpochMillis()
        );
        return gson.toJson(record).getBytes(StandardCharsets.UTF_8);
    }

    KeyedQueueEntry<QueuedPulledDispatch> decodeEntry(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        DecodedRedisQueuedPulledDispatchRecord record = gson.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                DecodedRedisQueuedPulledDispatchRecord.class
        );
        if (record == null
                || record.deliveryId == null
                || record.selectedWorkerId == null
                || record.payload == null
                || record.correlationRef == null) {
            throw new IllegalArgumentException("encoded queued dispatch record is incomplete");
        }
        QueuedPulledDispatch item = new QueuedPulledDispatch(
                record.deliveryId,
                record.selectedWorkerId,
                record.payload,
                record.correlationRef,
                record.createdAtEpochMillis
        );
        return new KeyedQueueEntry<>(item, item.createdAtEpochMillis());
    }

    private static String encodeKeyToken(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("key token must not be blank");
        }
        return KEY_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKeyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("encoded key token must not be blank");
        }
        return new String(KEY_DECODER.decode(token), StandardCharsets.UTF_8);
    }

    private record RedisQueuedPulledDispatchRecord(String deliveryId,
                                                   String selectedWorkerId,
                                                   String payload,
                                                   String correlationRef,
                                                   long createdAtEpochMillis) {
    }

    private static final class DecodedRedisQueuedPulledDispatchRecord {
        private String deliveryId;
        private String selectedWorkerId;
        private String payload;
        private String correlationRef;
        private long createdAtEpochMillis;
    }
}
