package com.xa.mass.sdk.worker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

final class WorkerActionPayloadDecoder {

    private static final String ACTION_ID_FIELD = "actionId";
    private static final String REPLY_REF_FIELD = "replyRef";
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String BODY_FIELD = "body";
    private static final String SHARED_CONFIG_FIELD = "sharedConfig";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;

    WorkerActionPayloadDecoder() {
        this(new GsonBuilder().create());
    }

    WorkerActionPayloadDecoder(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    WorkerAction decode(PulledDeliveryMessage message) {
        Objects.requireNonNull(message, "message");
        return decode(message.getPayload(), message.getDeliveryId(), message.getCorrelationRef());
    }

    WorkerAction decode(String payload, String actionId, String replyRef) {
        JsonObject frame = gson.fromJson(requireText(payload, "payload"), JsonObject.class);
        if (frame == null) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        return new WorkerAction(
                firstNonBlank(readString(frame, ACTION_ID_FIELD), actionId),
                firstNonBlank(readString(frame, REPLY_REF_FIELD), replyRef),
                readString(frame, EVENT_CODE_FIELD),
                readBody(frame),
                readMap(frame, SHARED_CONFIG_FIELD)
        );
    }

    private String readBody(JsonObject frame) {
        if (frame == null || !frame.has(BODY_FIELD) || frame.get(BODY_FIELD).isJsonNull()) {
            return "{}";
        }
        try {
            if (frame.get(BODY_FIELD).isJsonPrimitive()) {
                return frame.get(BODY_FIELD).getAsString();
            }
            return gson.toJson(frame.get(BODY_FIELD));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private Map<String, Object> readMap(JsonObject frame, String field) {
        if (frame == null || field == null || !frame.has(field) || frame.get(field).isJsonNull()) {
            return Map.of();
        }
        Map<String, Object> decoded = gson.fromJson(frame.get(field), MAP_TYPE);
        return TransportJsonValueNormalizer.normalizeObject(decoded, field);
    }

    private static String readString(JsonObject frame, String field) {
        if (frame == null || field == null || !frame.has(field) || frame.get(field).isJsonNull()) {
            return null;
        }
        try {
            String value = frame.get(field).getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = firstNonBlank(value, null);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }
}
