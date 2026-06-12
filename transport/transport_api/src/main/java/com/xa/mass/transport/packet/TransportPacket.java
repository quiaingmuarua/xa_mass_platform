package com.xa.mass.transport.packet;

import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

public final class TransportPacket {

    public static final int CURRENT_VERSION = 1;
    public static final String JSON_CONTENT_TYPE = "application/json";
    public static final String PAYLOAD_TASK_NAME = "taskName";
    public static final String PAYLOAD_PROJECT = "project";
    public static final String PAYLOAD_USER_ID = "userId";
    public static final String PAYLOAD_RETRY_COUNT = "retryCount";
    public static final String PAYLOAD_ATTEMPT_NO = "attemptNo";
    public static final String PAYLOAD_WORKER_ID = "workerId";
    public static final String PAYLOAD_BATCH_ID = "batchId";
    public static final String PAYLOAD_INPUT = "input";
    public static final String PAYLOAD_SHARED_CONFIG = "sharedConfig";
    public static final String PAYLOAD_SUCCESS = "success";
    public static final String PAYLOAD_DETAIL = "detail";
    public static final String PAYLOAD_ERROR_CODE = "errorCode";
    public static final String PAYLOAD_OUTPUT = "output";

    private final int version;
    private final String packetId;
    private final String traceId;
    private final PacketType type;
    private final String adapterId;
    private final String routeKey;
    private final String taskId;
    private final String messageId;
    private final String attemptId;
    private final String eventCode;
    private final String contentType;
    private final Map<String, Object> payload;

    public TransportPacket(int version,
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
        this(version, packetId, traceId, type, adapterId, routeKey, taskId, messageId, attemptId, eventCode, contentType, payload, false);
    }

    public static TransportPacket fromDecodedJson(int version,
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
                payload,
                true
        );
    }

    private TransportPacket(int version,
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
                            Map<String, Object> payload,
                            boolean trustedDecodedPayload) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        this.version = version;
        this.packetId = requireText(packetId, "packetId");
        this.type = Objects.requireNonNull(type, "type");
        this.traceId = normalize(traceId);
        this.adapterId = TransportDeliveryAddressing.normalizeAdapterId(adapterId);
        this.routeKey = TransportDeliveryAddressing.normalizeRouteKey(routeKey);
        this.taskId = normalize(taskId);
        this.messageId = normalize(messageId);
        this.attemptId = normalize(attemptId);
        this.eventCode = normalize(eventCode);
        this.contentType = requireText(contentType, "contentType");
        this.payload = trustedDecodedPayload
                ? TransportJsonValueNormalizer.freezeDecodedObject(payload)
                : TransportJsonValueNormalizer.normalizeObject(payload, "payload");
        validateTypeSpecificIdentity();
    }

    public int version() {
        return version;
    }

    public String packetId() {
        return packetId;
    }

    public String traceId() {
        return traceId;
    }

    public PacketType type() {
        return type;
    }

    public String adapterId() {
        return adapterId;
    }

    public String routeKey() {
        return routeKey;
    }

    public String taskId() {
        return taskId;
    }

    public String messageId() {
        return messageId;
    }

    public String attemptId() {
        return attemptId;
    }

    public String eventCode() {
        return eventCode;
    }

    public String contentType() {
        return contentType;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String payloadString(String key) {
        return stringValue(payload.get(key));
    }

    public int payloadInt(String key) {
        return intValue(payload.get(key));
    }

    public boolean payloadBoolean(String key) {
        return Boolean.TRUE.equals(payload.get(key));
    }

    public Map<String, Object> payloadObject(String key) {
        return mapValue(payload.get(key));
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
                payload,
                true
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private void validateTypeSpecificIdentity() {
        switch (type) {
            case TASK_DISPATCH -> {
                requireNormalized(taskId, "taskId");
                requireNormalized(messageId, "messageId");
            }
            case TASK_RESULT -> {
                requireNormalized(taskId, "taskId");
                requireNormalized(messageId, "messageId");
            }
            case WORKER_SYSTEM_EVENT -> requireNormalized(eventCode, "eventCode");
        }
    }

    private static void requireNormalized(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransportPacket that)) {
            return false;
        }
        return version == that.version
                && Objects.equals(packetId, that.packetId)
                && Objects.equals(traceId, that.traceId)
                && type == that.type
                && Objects.equals(adapterId, that.adapterId)
                && Objects.equals(routeKey, that.routeKey)
                && Objects.equals(taskId, that.taskId)
                && Objects.equals(messageId, that.messageId)
                && Objects.equals(attemptId, that.attemptId)
                && Objects.equals(eventCode, that.eventCode)
                && Objects.equals(contentType, that.contentType)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
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

    @Override
    public String toString() {
        return "TransportPacket{"
                + "version=" + version
                + ", packetId='" + packetId + '\''
                + ", traceId='" + traceId + '\''
                + ", type=" + type
                + ", adapterId='" + adapterId + '\''
                + ", routeKey='" + routeKey + '\''
                + ", taskId='" + taskId + '\''
                + ", messageId='" + messageId + '\''
                + ", attemptId='" + attemptId + '\''
                + ", eventCode='" + eventCode + '\''
                + ", contentType='" + contentType + '\''
                + ", payload=" + payload
                + '}';
    }
}
