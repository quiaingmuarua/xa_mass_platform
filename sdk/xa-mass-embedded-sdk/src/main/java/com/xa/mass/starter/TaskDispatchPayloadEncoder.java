package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

final class TaskDispatchPayloadEncoder {

    private static final String RESULT_CORRELATION_REF_FIELD = "resultCorrelationRef";
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String INPUT_FIELD = "input";
    private static final String SHARED_CONFIG_FIELD = "sharedConfig";

    private final Gson gson;

    TaskDispatchPayloadEncoder() {
        this(new GsonBuilder().create());
    }

    TaskDispatchPayloadEncoder(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    String encode(TaskDispatchContext task, TaskDispatchBinding binding, String resultCorrelationRef) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(binding, "binding");
        JsonObject frame = new JsonObject();
        frame.addProperty(RESULT_CORRELATION_REF_FIELD, requireText(resultCorrelationRef, RESULT_CORRELATION_REF_FIELD));
        String eventCode = firstNonBlank(binding.eventCode(), task.eventCode());
        if (eventCode != null) {
            frame.addProperty(EVENT_CODE_FIELD, eventCode);
        }
        frame.add(INPUT_FIELD, gson.toJsonTree(normalizeInput(binding.payload())));
        frame.add(SHARED_CONFIG_FIELD, gson.toJsonTree(
                TransportJsonValueNormalizer.normalizeObject(task.sharedConfig(), SHARED_CONFIG_FIELD)
        ));
        return gson.toJson(frame);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeInput(Map<String, Object> rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return Map.of();
        }
        if (isWrappedJsonPayload(rawInput)) {
            return TransportJsonValueNormalizer.normalizeObject((Map<String, Object>) rawInput.get("data"), INPUT_FIELD);
        }
        if (isWrappedTextPayload(rawInput)) {
            return Map.of("text", rawInput.get("text"));
        }
        return TransportJsonValueNormalizer.normalizeObject(rawInput, INPUT_FIELD);
    }

    private boolean isWrappedJsonPayload(Map<String, Object> rawInput) {
        Object data = rawInput.get("data");
        if (!(data instanceof Map<?, ?>)) {
            return false;
        }
        Object type = rawInput.get("type");
        return type instanceof String text && "json".equalsIgnoreCase(text);
    }

    private boolean isWrappedTextPayload(Map<String, Object> rawInput) {
        Object text = rawInput.get("text");
        if (!(text instanceof String)) {
            return false;
        }
        Object type = rawInput.get("type");
        return type instanceof String value && "text".equalsIgnoreCase(value);
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
