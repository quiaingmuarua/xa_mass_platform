package com.xa.mass.transport.model;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

/**
 * Minimal task-dispatch content carried by assigned delivery commands.
 */
public final class TaskDispatchContent {

    private final String taskId;
    private final String messageId;
    private final String eventCode;
    private final Map<String, Object> input;
    private final Map<String, Object> sharedConfig;

    public TaskDispatchContent(String taskId,
                               String messageId,
                               String eventCode,
                               Map<String, Object> input,
                               Map<String, Object> sharedConfig) {
        this.taskId = requireText(taskId, "taskId");
        this.messageId = requireText(messageId, "messageId");
        this.eventCode = optionalText(eventCode);
        this.input = normalizeInput(input);
        this.sharedConfig = normalizeObject(sharedConfig, TransportPacket.PAYLOAD_SHARED_CONFIG);
    }

    public static TaskDispatchContent from(TaskDispatchContext task, TaskDispatchBinding binding) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(binding, "binding");
        return new TaskDispatchContent(
                task.taskId(),
                binding.messageId(),
                firstNonBlank(binding.eventCode(), task.eventCode()),
                binding.payload(),
                task.sharedConfig()
        );
    }

    public String taskId() {
        return taskId;
    }

    public String messageId() {
        return messageId;
    }

    public String eventCode() {
        return eventCode;
    }

    public Map<String, Object> input() {
        return input;
    }

    public Map<String, Object> sharedConfig() {
        return sharedConfig;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> normalizeInput(Map<String, Object> rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return Map.of();
        }
        if (isWrappedJsonPayload(rawInput)) {
            Object data = rawInput.get("data");
            return normalizeObject((Map<String, Object>) data, TransportPacket.PAYLOAD_INPUT);
        }
        if (isWrappedTextPayload(rawInput)) {
            return Map.of("text", rawInput.get("text"));
        }
        return normalizeObject(rawInput, TransportPacket.PAYLOAD_INPUT);
    }

    private static Map<String, Object> normalizeObject(Map<String, Object> values, String fieldName) {
        return TransportJsonValueNormalizer.normalizeObject(values, fieldName);
    }

    private static boolean isWrappedJsonPayload(Map<String, Object> rawInput) {
        Object data = rawInput.get("data");
        if (!(data instanceof Map<?, ?>)) {
            return false;
        }
        Object type = rawInput.get("type");
        return type instanceof String text && "json".equalsIgnoreCase(text);
    }

    private static boolean isWrappedTextPayload(Map<String, Object> rawInput) {
        Object text = rawInput.get("text");
        if (!(text instanceof String)) {
            return false;
        }
        Object type = rawInput.get("type");
        return type instanceof String value && "text".equalsIgnoreCase(value);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
