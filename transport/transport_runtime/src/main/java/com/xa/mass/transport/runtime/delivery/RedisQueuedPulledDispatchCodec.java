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
    private static final Base64.Encoder VALUE_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder VALUE_DECODER = Base64.getDecoder();
    private static final char STORED_VALUE_DELIMITER = '|';

    private final Gson gson;

    RedisQueuedPulledDispatchCodec() {
        this(new GsonBuilder().create());
    }

    RedisQueuedPulledDispatchCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encodeKeyPart(DeliveryQueueKey key) {
        Objects.requireNonNull(key, "key");
        return encodeKeyToken(key.deliveryQueueKey());
    }

    DeliveryQueueKey decodeKeyPart(String encodedKeyPart) {
        if (encodedKeyPart == null || encodedKeyPart.isBlank()) {
            throw new IllegalArgumentException("encodedKeyPart must not be blank");
        }
        return new DeliveryQueueKey(decodeKeyToken(encodedKeyPart));
    }

    String encodeSelectedWorkerToken(String selectedWorkerId) {
        return encodeKeyToken(selectedWorkerId);
    }

    String encodeStoredValue(KeyedQueueEntry<QueuedPulledDispatch> entry) {
        Objects.requireNonNull(entry, "entry");
        QueuedPulledDispatch item = Objects.requireNonNull(entry.value(), "entry.value");
        String encodedValue = VALUE_ENCODER.encodeToString(encodeEntry(entry));
        return entry.createdAtEpochMillis()
                + String.valueOf(STORED_VALUE_DELIMITER)
                + encodeSelectedWorkerToken(item.selectedWorkerId())
                + STORED_VALUE_DELIMITER
                + encodedValue;
    }

    KeyedQueueEntry<QueuedPulledDispatch> decodeStoredValue(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            throw new IllegalArgumentException("stored queue value must not be blank");
        }
        int firstDelimiter = storedValue.indexOf(STORED_VALUE_DELIMITER);
        int secondDelimiter = storedValue.indexOf(STORED_VALUE_DELIMITER, firstDelimiter + 1);
        if (firstDelimiter <= 0 || secondDelimiter <= firstDelimiter || secondDelimiter == storedValue.length() - 1) {
            throw new IllegalArgumentException("stored queue value is malformed");
        }
        String encodedValue = storedValue.substring(secondDelimiter + 1);
        return decodeEntry(VALUE_DECODER.decode(encodedValue));
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
