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

final class PulledTaskDispatchPayloadDecoder {

    private static final String RESULT_CORRELATION_REF_FIELD = "resultCorrelationRef";
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String INPUT_FIELD = "input";
    private static final String SHARED_CONFIG_FIELD = "sharedConfig";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;

    PulledTaskDispatchPayloadDecoder() {
        this(new GsonBuilder().create());
    }

    PulledTaskDispatchPayloadDecoder(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    PulledTaskDispatch decode(PulledDeliveryMessage message) {
        Objects.requireNonNull(message, "message");
        return decode(message.getPayload(), message.getCorrelationRef());
    }

    PulledTaskDispatch decode(String payload, String correlationRef) {
        JsonObject frame = gson.fromJson(requireText(payload, "payload"), JsonObject.class);
        if (frame == null) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        return new PulledTaskDispatch(
                firstNonBlank(readString(frame, RESULT_CORRELATION_REF_FIELD), correlationRef),
                readString(frame, EVENT_CODE_FIELD),
                readMap(frame, INPUT_FIELD),
                readMap(frame, SHARED_CONFIG_FIELD)
        );
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
