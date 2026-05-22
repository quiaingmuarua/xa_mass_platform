package com.xa.mass.engine.worker;

import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Kernel-targeted event handler for worker state reports.
 *
 * <p>The handler parses event payloads and delegates projection mutation to
 * {@link WorkerStateProjectionOwner}. It does not write reachability, load,
 * matching, or task-result truth.</p>
 */
public final class WorkerStateReportEventHandler {

    public static final String EVENT_CODE = "kernel.worker.state.report";

    private final WorkerControlService workerControlService;

    public WorkerStateReportEventHandler(WorkerControlService workerControlService) {
        this.workerControlService = Objects.requireNonNull(workerControlService, "workerControlService");
    }

    public void register(KernelEventHandlerRegistry registry) {
        Objects.requireNonNull(registry, "registry").registerWorkerManagerEvent(EVENT_CODE, this::handle);
    }

    public CoreEventResponse handle(CoreEventRequest request, com.xa.mass.command.event.CoreEventPrincipal principal) {
        try {
            WorkerStateProjectionResult result = workerControlService.applyWorkerStateReport(reportFrom(request.getPayload()));
            if (result.success()) {
                return CoreEventResponse.success(responsePayload(result), request.getRequestId());
            }
            return CoreEventResponse.failure(result.status().name(), result.reason(), request.getRequestId());
        } catch (IllegalArgumentException e) {
            return CoreEventResponse.failure("INVALID_WORKER_STATE_REPORT", e.getMessage(),
                    request != null ? request.getRequestId() : null);
        }
    }

    private static WorkerStateReport reportFrom(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        String workerId = stringValue(payload.get("workerId"), "workerId");
        long stateVersion = longValue(payload.get("stateVersion"), "stateVersion");
        return WorkerStateReport.builder(workerId, stateVersion, stringValue(payload.get("state"), "state"))
                .reason(optionalString(payload.get("reason")))
                .observedAt(optionalInstant(payload.get("observedAtEpochMillis"), "observedAtEpochMillis"))
                .attributes(stringMap(payload.get("attributes"), "attributes"))
                .build();
    }

    private static Map<String, Object> responsePayload(WorkerStateProjectionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status().name());
        payload.put("workerId", result.workerId());
        payload.put("stateVersion", result.stateVersion());
        payload.put("projectionChanged", result.projectionChanged());
        payload.put("reason", result.reason());
        if (result.projection() != null) {
            payload.put("state", result.projection().state());
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

    private static Map<String, String> stringMap(Object value, String fieldName) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(fieldName + " must be an object");
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = optionalString(entry.getKey());
            String item = optionalString(entry.getValue());
            if (key != null && item != null) {
                values.put(key, item);
            }
        }
        return values;
    }
}
