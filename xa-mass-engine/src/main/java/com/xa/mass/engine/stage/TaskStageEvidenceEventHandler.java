package com.xa.mass.engine.stage;

import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Kernel-targeted event handler for task stage evidence.
 *
 * <p>The handler parses event payloads and delegates to
 * {@link TaskStageEvidenceOwner}. It does not commit public results or mutate
 * task-work runtime finality.</p>
 */
public final class TaskStageEvidenceEventHandler {

    public static final String EVENT_CODE = "kernel.task.stage.evidence";

    private final TaskStageEvidenceService stageEvidenceService;

    public TaskStageEvidenceEventHandler(TaskStageEvidenceService stageEvidenceService) {
        this.stageEvidenceService = Objects.requireNonNull(stageEvidenceService, "stageEvidenceService");
    }

    public void register(KernelEventHandlerRegistry registry) {
        Objects.requireNonNull(registry, "registry").registerTaskEngineEvent(EVENT_CODE, this::handle);
    }

    public CoreEventResponse handle(CoreEventRequest request, CoreEventPrincipal principal) {
        try {
            TaskStageEvidenceResult result = stageEvidenceService.applyEvidence(evidenceFrom(request.getPayload()));
            if (result.success()) {
                return CoreEventResponse.success(responsePayload(result), request.getRequestId());
            }
            return CoreEventResponse.failure(result.status().name(), result.reason(), request.getRequestId());
        } catch (IllegalArgumentException e) {
            return CoreEventResponse.failure("INVALID_TASK_STAGE_EVIDENCE", e.getMessage(),
                    request != null ? request.getRequestId() : null);
        }
    }

    private static TaskStageEvidence evidenceFrom(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String taskId = stringValue(payload.get("taskId"), "taskId");
        String messageId = stringValue(payload.get("messageId"), "messageId");
        String stageName = stringValue(payload.get("stageName"), "stageName");
        long stageVersion = longValue(payload.get("stageVersion"), "stageVersion");
        return TaskStageEvidence.builder(taskId, messageId, stageName, stageVersion)
                .stageStatus(stringValue(payload.get("stageStatus"), "stageStatus"))
                .detail(optionalString(payload.get("detail")))
                .observedAt(optionalInstant(payload.get("observedAtEpochMillis"), "observedAtEpochMillis"))
                .attributes(objectMap(payload.get("attributes"), "attributes"))
                .build();
    }

    private static Map<String, Object> responsePayload(TaskStageEvidenceResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status().name());
        payload.put("taskId", result.taskId());
        payload.put("messageId", result.messageId());
        payload.put("stageName", result.stageName());
        payload.put("stageVersion", result.stageVersion());
        payload.put("projectionChanged", result.projectionChanged());
        payload.put("reason", result.reason());
        if (result.projection() != null) {
            payload.put("stageStatus", result.projection().stageStatus());
        }
        return payload;
    }

    private static String stringValue(Object value, String fieldName) {
        String text = optionalString(value);
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static long longValue(Object value, String fieldName) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = optionalString(value);
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a number", e);
        }
    }

    private static Instant optionalInstant(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        return Instant.ofEpochMilli(longValue(value, fieldName));
    }

    private static Map<String, Object> objectMap(Object value, String fieldName) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(fieldName + " must be an object");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = optionalString(entry.getKey());
            if (key != null) {
                values.put(key, entry.getValue());
            }
        }
        return values;
    }
}
