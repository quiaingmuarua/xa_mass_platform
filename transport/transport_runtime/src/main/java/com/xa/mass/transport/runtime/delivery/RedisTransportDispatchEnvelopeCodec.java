package com.xa.mass.transport.runtime.delivery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

final class RedisTransportDispatchEnvelopeCodec {

    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder KEY_DECODER = Base64.getUrlDecoder();
    private static final String WORKER_INDEX_SEPARATOR = ":worker-index:";

    private final Gson gson;

    RedisTransportDispatchEnvelopeCodec() {
        this(new GsonBuilder().create());
    }

    RedisTransportDispatchEnvelopeCodec(Gson gson) {
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

    byte[] encodeEntry(KeyedQueueEntry<TransportDispatchEnvelope> entry) {
        Objects.requireNonNull(entry, "entry");
        TransportDispatchEnvelope envelope = Objects.requireNonNull(entry.value(), "entry.value");
        RedisTransportDispatchEnvelopeRecord record = new RedisTransportDispatchEnvelopeRecord(
                envelope.getDeliveryId(),
                envelope.getSelectedWorkerId(),
                envelope.getCreatedAtEpochMillis(),
                envelope.getPacket()
        );
        return gson.toJson(record).getBytes(StandardCharsets.UTF_8);
    }

    KeyedQueueEntry<TransportDispatchEnvelope> decodeEntry(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        DecodedRedisTransportDispatchEnvelopeRecord record = gson.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                DecodedRedisTransportDispatchEnvelopeRecord.class
        );
        if (record == null
                || record.deliveryId == null
                || record.selectedWorkerId == null
                || record.packet == null) {
            throw new IllegalArgumentException("encoded dispatch envelope record is incomplete");
        }
        TransportPacket packet = TransportPacket.fromDecodedJson(
                record.packet.version,
                record.packet.packetId,
                record.packet.traceId,
                record.packet.type,
                record.packet.adapterId,
                record.packet.routeKey,
                record.packet.taskId,
                record.packet.messageId,
                record.packet.attemptId,
                record.packet.eventCode,
                record.packet.contentType,
                record.packet.payload
        );
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                record.deliveryId,
                record.selectedWorkerId,
                packet,
                record.createdAtEpochMillis
        );
        return new KeyedQueueEntry<>(envelope, record.createdAtEpochMillis);
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

    private static final class DecodedRedisTransportDispatchEnvelopeRecord {
        private String deliveryId;
        private String selectedWorkerId;
        private long createdAtEpochMillis;
        private DecodedTransportPacketRecord packet;
    }

    private static final class DecodedTransportPacketRecord {
        private int version;
        private String packetId;
        private String traceId;
        private PacketType type;
        private String adapterId;
        private String routeKey;
        private String taskId;
        private String messageId;
        private String attemptId;
        private String eventCode;
        private String contentType;
        private Map<String, Object> payload;
    }
}
