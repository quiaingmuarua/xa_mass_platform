package com.xa.mass.engine.command;

import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.worker.WorkerControlService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Kernel-targeted event entry for worker command requests.
 *
 * <p>The handler owns event payload parsing only. Command lifecycle truth
 * remains in {@link WorkerCommandLifecycleOwner}; delivery and acknowledgement
 * ingress are future owner extensions.</p>
 */
public final class WorkerCommandRequestEventHandler {

    public static final String EVENT_CODE = "kernel.worker.command.request";

    private final WorkerControlService workerControlService;

    public WorkerCommandRequestEventHandler(WorkerControlService workerControlService) {
        this.workerControlService = Objects.requireNonNull(workerControlService, "workerControlService");
    }

    public void register(KernelEventHandlerRegistry registry) {
        Objects.requireNonNull(registry, "registry").registerWorkerManagerEvent(EVENT_CODE, this::handle);
    }

    public CoreEventResponse handle(CoreEventRequest request, CoreEventPrincipal principal) {
        try {
            WorkerCommandLifecycleResult result = workerControlService.requestWorkerCommand(commandRequestFrom(request));
            if (result.success()) {
                return CoreEventResponse.success(responsePayload(result), request.getRequestId());
            }
            return CoreEventResponse.failure(result.code().name(), result.reason(), request.getRequestId());
        } catch (IllegalArgumentException e) {
            return CoreEventResponse.failure("INVALID_WORKER_COMMAND_REQUEST", e.getMessage(),
                    request != null ? request.getRequestId() : null);
        }
    }

    private static WorkerCommandRequest commandRequestFrom(CoreEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Map<String, Object> payload = request.getPayload();
        return WorkerCommandRequest.builder(
                        stringValue(payload.get("commandId"), "commandId"),
                        stringValue(payload.get("workerId"), "workerId"),
                        stringValue(payload.get("commandType"), "commandType"))
                .requester(optionalString(payload.get("requester")))
                .reason(optionalString(payload.get("reason")))
                .idempotencyKey(optionalString(payload.get("idempotencyKey")))
                .deadlineEpochMillis(optionalLong(payload.get("deadlineEpochMillis"), "deadlineEpochMillis"))
                .payload(nestedPayload(payload.get("payload")))
                .build();
    }

    private static Map<String, Object> responsePayload(WorkerCommandLifecycleResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", result.code().name());
        payload.put("commandId", result.record().commandId());
        payload.put("workerId", result.record().workerId());
        payload.put("commandType", result.record().commandType());
        payload.put("status", result.record().status().name());
        payload.put("reason", result.reason());
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

    private static Long optionalLong(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = optionalString(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a number", e);
        }
    }

    private static Map<String, Object> nestedPayload(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("payload must be an object");
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = optionalString(entry.getKey());
            if (key != null) {
                payload.put(key, entry.getValue());
            }
        }
        return payload;
    }
}
