package com.xa.mass.transport.packet;

import com.xa.mass.transport.model.TransportDeliveryAddressing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TransportPacket(int version,
                              String packetId,
                              String traceId,
                              PacketType type,
                              String adapterId,
                              String routeKey,
                              String taskId,
                              String messageId,
                              String attemptId,
                              String eventCode,
                              String contentType,
                              Map<String, Object> payload) {

    public static final int CURRENT_VERSION = 1;
    public static final String JSON_CONTENT_TYPE = "application/json";

    public TransportPacket {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        packetId = requireText(packetId, "packetId");
        type = Objects.requireNonNull(type, "type");
        traceId = normalize(traceId);
        adapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        taskId = normalize(taskId);
        messageId = normalize(messageId);
        attemptId = normalize(attemptId);
        eventCode = normalize(eventCode);
        contentType = requireText(contentType, "contentType");
        payload = immutablePayload(payload);
    }

    public TransportPacket withTransportAddress(String adapterId, String routeKey) {
        return new TransportPacket(
                version,
                packetId,
                traceId,
                type,
                adapterId,
                routeKey,
                taskId,
                messageId,
                attemptId,
                eventCode,
                contentType,
                payload
        );
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(payload));
    }
}
