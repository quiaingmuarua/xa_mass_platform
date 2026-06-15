package com.xa.mass.sdk.worker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

/**
 * SDK/starter-owned codec for the opaque worker dispatch payload carried by
 * transport.
 */
public final class TaskDispatchPayloadCodec {

    private static final String MESSAGE_ID_FIELD = "messageId";
    private static final String WORKER_ID_FIELD = "workerId";
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final String TASK_ID_FIELD = "taskId";
    private static final String RETRY_COUNT_FIELD = "retryCount";
    private static final String BATCH_ID_FIELD = "batchId";
    private static final String INPUT_FIELD = "input";
    private static final String SHARED_CONFIG_FIELD = "sharedConfig";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;
    private final TaskDispatchDeliveryCorrelationCodec correlationCodec;

    public TaskDispatchPayloadCodec() {
        this(new GsonBuilder().create(), new TaskDispatchDeliveryCorrelationCodec());
    }

    TaskDispatchPayloadCodec(Gson gson, TaskDispatchDeliveryCorrelationCodec correlationCodec) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.correlationCodec = Objects.requireNonNull(correlationCodec, "correlationCodec");
    }

    public String encode(TaskDispatchContext task, TaskDispatchBinding binding, String selectedWorkerId) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(binding, "binding");
        JsonObject frame = new JsonObject();
        frame.addProperty(MESSAGE_ID_FIELD, requireText(binding.messageId(), "messageId"));
        put(frame, WORKER_ID_FIELD, selectedWorkerId);
        String eventCode = firstNonBlank(binding.eventCode(), task.eventCode());
        if (eventCode != null) {
            frame.addProperty(EVENT_CODE_FIELD, eventCode);
        }
        frame.addProperty(TASK_ID_FIELD, requireText(task.taskId(), "taskId"));
        frame.addProperty(RETRY_COUNT_FIELD, Math.max(0, binding.retryCount()));
        put(frame, BATCH_ID_FIELD, binding.batchId());
        frame.add(INPUT_FIELD, gson.toJsonTree(normalizeInput(binding.payload())));
        frame.add(SHARED_CONFIG_FIELD, gson.toJsonTree(
                TransportJsonValueNormalizer.normalizeObject(task.sharedConfig(), SHARED_CONFIG_FIELD)
        ));
        return gson.toJson(frame);
    }

    public PulledTaskDispatch decode(PulledDeliveryMessage message) {
        Objects.requireNonNull(message, "message");
        return decode(message.getPayload(), message.getCorrelationRef());
    }

    public PulledTaskDispatch decode(String payload, String correlationRef) {
        TaskDispatchDeliveryCorrelation correlation = correlationCodec.decode(correlationRef);
        JsonObject frame = gson.fromJson(requireText(payload, "payload"), JsonObject.class);
        if (frame == null) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        return new PulledTaskDispatch(
                firstNonBlank(readString(frame, TASK_ID_FIELD), correlation.taskId()),
                firstNonBlank(readString(frame, MESSAGE_ID_FIELD), correlation.messageId()),
                readString(frame, EVENT_CODE_FIELD),
                readMap(frame, INPUT_FIELD),
                readMap(frame, SHARED_CONFIG_FIELD),
                correlation.attemptId(),
                correlation.attemptNo(),
                readNonNegativeInt(frame, RETRY_COUNT_FIELD),
                readString(frame, BATCH_ID_FIELD)
        );
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

    private Map<String, Object> readMap(JsonObject frame, String field) {
        if (frame == null || field == null || !frame.has(field) || frame.get(field).isJsonNull()) {
            return Map.of();
        }
        Map<String, Object> decoded = gson.fromJson(frame.get(field), MAP_TYPE);
        return TransportJsonValueNormalizer.normalizeObject(decoded, field);
    }

    private int readNonNegativeInt(JsonObject frame, String field) {
        if (frame == null || !frame.has(field) || frame.get(field).isJsonNull()) {
            return 0;
        }
        try {
            return Math.max(0, frame.get(field).getAsInt());
        } catch (Exception ignored) {
            return 0;
        }
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

    private static void put(JsonObject frame, String field, String value) {
        String normalized = firstNonBlank(value, null);
        if (normalized != null) {
            frame.addProperty(field, normalized);
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
