package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

final class RedisTransportDispatchEnvelopeCodec {

    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder KEY_DECODER = Base64.getUrlDecoder();

    private final Gson gson;

    RedisTransportDispatchEnvelopeCodec() {
        this(new GsonBuilder().create());
    }

    RedisTransportDispatchEnvelopeCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encodeKeyPart(DeliveryQueueKey key) {
        Objects.requireNonNull(key, "key");
        return encodeKeyToken(key.adapterId()) + ":" + encodeKeyToken(key.routeKey());
    }

    DeliveryQueueKey decodeKeyPart(String encodedKeyPart) {
        if (encodedKeyPart == null || encodedKeyPart.isBlank()) {
            throw new IllegalArgumentException("encodedKeyPart must not be blank");
        }
        int delimiter = encodedKeyPart.indexOf(':');
        if (delimiter <= 0 || delimiter == encodedKeyPart.length() - 1) {
            throw new IllegalArgumentException("encodedKeyPart must contain adapter and route tokens");
        }
        String adapterToken = encodedKeyPart.substring(0, delimiter);
        String routeToken = encodedKeyPart.substring(delimiter + 1);
        return new DeliveryQueueKey(decodeKeyToken(adapterToken), decodeKeyToken(routeToken));
    }

    byte[] encodeEntry(KeyedQueueEntry<TransportDispatchEnvelope> entry) {
        Objects.requireNonNull(entry, "entry");
        TransportDispatchEnvelope envelope = Objects.requireNonNull(entry.value(), "entry.value");
        RedisTransportDispatchEnvelopeRecord record = new RedisTransportDispatchEnvelopeRecord(
                envelope.getDeliveryId(),
                envelope.getCreatedAtEpochMillis(),
                envelope.getPacket()
        );
        return gson.toJson(record).getBytes(StandardCharsets.UTF_8);
    }

    KeyedQueueEntry<TransportDispatchEnvelope> decodeEntry(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        RedisTransportDispatchEnvelopeRecord record = gson.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                RedisTransportDispatchEnvelopeRecord.class
        );
        if (record == null || record.deliveryId() == null || record.packet() == null) {
            throw new IllegalArgumentException("encoded dispatch envelope record is incomplete");
        }
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                record.deliveryId(),
                record.packet(),
                record.createdAtEpochMillis()
        );
        return new KeyedQueueEntry<>(envelope, record.createdAtEpochMillis());
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
}
